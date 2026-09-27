//! Full vertical-slice integration test.
//!
//! This is the one that matters most: it proves the *entire* pipeline the
//! product brief asked for, end to end, over real sockets — not just that
//! the crypto primitives work in isolation (that's device/src/lib.rs's unit
//! tests) but that identity generation, prekey registration, bundle fetch,
//! X3DH, Double Ratchet encrypt/decrypt, relay delivery, and local encrypted
//! persistence all actually fit together.
//!
//! Flow exercised (mirrors the product brief's numbered list exactly):
//!  1. generate identity                -> Device::new
//!  2. register public identity/prekeys  -> Device::generate_and_register_prekeys + relay_client::register
//!  3. obtain peer's prekey bundle       -> relay_client::fetch_bundle
//!  4. establish X3DH session            -> Device::establish_session_from_bundle
//!  5. initialize Double Ratchet         -> (implicit in the above)
//!  6. encrypt one text message          -> Device::encrypt
//!  7. send ciphertext through relay     -> relay_client::send_ciphertext
//!  8. Device B receives it              -> relay_client::pull_messages
//!  9. decrypts it                       -> Device::decrypt
//! 10. stores it in local SQLCipher      -> local_store::LocalStore::insert_message

use std::time::{SystemTime, UNIX_EPOCH};

use device::relay_client;
use device::{Device, DEVICE_ID};
use local_store::{Direction, LocalStore};

fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64
}

fn random_hex_key() -> String {
    use std::io::Read;
    let mut f = std::fs::File::open("/dev/urandom").unwrap();
    let mut buf = [0u8; 32];
    f.read_exact(&mut buf).unwrap();
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

fn temp_db_path(tag: &str) -> String {
    format!(
        "{}/genzo_e2e_{}_{}_{}.db",
        std::env::temp_dir().display(),
        tag,
        std::process::id(),
        now_millis()
    )
}

#[test]
fn full_two_device_flow_through_the_relay_with_local_persistence() {
    // --- spin up a real relay on an OS-assigned localhost port ---
    let (addr, _state) = relay::spawn("127.0.0.1:0").expect("failed to start relay");
    let host_port = addr.to_string();
    std::thread::sleep(std::time::Duration::from_millis(50));

    // --- Step 1: generate identity (two independent devices) ---
    let mut alice = Device::new("alice");
    let mut bob = Device::new("bob");

    // --- Step 2: register public identity/prekeys with the relay ---
    let alice_reg = alice.generate_and_register_prekeys().expect("alice prekey generation");
    let bob_reg = bob.generate_and_register_prekeys().expect("bob prekey generation");
    relay_client::register(&host_port, &alice_reg).expect("alice registers");
    relay_client::register(&host_port, &bob_reg).expect("bob registers");

    // --- Step 3: Alice obtains Bob's prekey bundle from the relay directory ---
    let bob_bundle = relay_client::fetch_bundle(&host_port, "bob").expect("fetch bob's bundle");
    assert_eq!(bob_bundle.user_id, "bob");
    assert!(bob_bundle.one_time_prekey_id.is_some(), "bob's one-time prekey should still be available");

    // A second fetch must NOT get the same one-time prekey again (it was
    // consumed by the first fetch) — "never reuse a one-time prekey".
    let bob_bundle_again = relay_client::fetch_bundle(&host_port, "bob").expect("fetch bob's bundle again");
    assert!(
        bob_bundle_again.one_time_prekey_id.is_none(),
        "a one-time prekey must be consumed after being handed out once"
    );

    // --- Steps 4-5: Alice establishes X3DH session + Double Ratchet init ---
    alice
        .establish_session_from_bundle(&bob_bundle)
        .expect("alice establishes session with bob");

    // --- Step 6: Alice encrypts one text message ---
    let plaintext = b"Hey Bob, this message went through X3DH + the relay + Double Ratchet!";
    let (message_type, ciphertext) = alice.encrypt("bob", DEVICE_ID, plaintext).expect("alice encrypts");

    // --- Step 7: send ciphertext through the Rust relay ---
    relay_client::send_ciphertext(&host_port, "bob", "alice", DEVICE_ID, message_type, &ciphertext, now_millis())
        .expect("send to bob failed");

    // --- Step 8: Device B receives it (polls the relay) ---
    let mut bob_inbox = relay_client::pull_messages(&host_port, "bob").expect("bob polls");
    assert_eq!(bob_inbox.len(), 1, "bob should have exactly one queued envelope");
    let envelope = bob_inbox.remove(0);
    assert_eq!(envelope.sender_id, "alice");
    assert_eq!(envelope.message_type, message_type);
    assert_eq!(envelope.ciphertext, ciphertext, "ciphertext must survive the relay hop byte-for-byte");

    // Server-side queue must be empty after delivery — no permanent
    // server-side ciphertext store (architecture doc §7).
    let bob_inbox_again = relay_client::pull_messages(&host_port, "bob").expect("bob polls again");
    assert!(bob_inbox_again.is_empty(), "relay must not retain envelopes after they've been delivered");

    // --- Step 9: Bob decrypts it ---
    let decrypted = bob
        .decrypt("alice", envelope.sender_device_id, envelope.message_type, &envelope.ciphertext)
        .expect("bob decrypts alice's message");
    assert_eq!(decrypted, plaintext, "decrypted plaintext must match what alice sent");

    // --- Step 10: Bob stores the resulting message in his local SQLCipher database ---
    let bob_db_path = temp_db_path("bob");
    let bob_db_key = random_hex_key(); // stand-in for an Android-Keystore-held key; see local_store docs
    let bob_store = LocalStore::open(&bob_db_path, &bob_db_key).expect("bob opens local store");
    let conversation_id = "conv:alice:bob";
    let plaintext_str = String::from_utf8(decrypted.clone()).unwrap();
    bob_store
        .insert_message(conversation_id, "alice", Direction::Incoming, &plaintext_str, now_millis() as i64)
        .expect("bob persists the message locally");

    // Re-open the DB fresh (simulating "app restarted") to prove this is
    // durable storage, not just an in-memory echo of what we just inserted.
    drop(bob_store);
    let bob_store_reopened = LocalStore::open(&bob_db_path, &bob_db_key).expect("reopen bob's store");
    let stored = bob_store_reopened
        .messages_for_conversation(conversation_id)
        .expect("read back bob's conversation");
    assert_eq!(stored.len(), 1);
    assert_eq!(stored[0].plaintext_body, plaintext_str);
    assert_eq!(stored[0].sender_id, "alice");
    assert_eq!(stored[0].direction, "incoming");

    // And prove it's genuinely at rest: the raw file must not contain the
    // plaintext that actually traveled through the full pipeline.
    let raw = std::fs::read(&bob_db_path).unwrap();
    let raw_str = String::from_utf8_lossy(&raw);
    assert!(
        !raw_str.contains("Hey Bob, this message went through"),
        "plaintext must not appear in the raw at-rest database bytes"
    );

    let _ = std::fs::remove_file(&bob_db_path);
}

#[test]
fn full_round_trip_reply_and_second_message_after_session_is_acknowledged() {
    let (addr, _state) = relay::spawn("127.0.0.1:0").expect("failed to start relay");
    let host_port = addr.to_string();
    std::thread::sleep(std::time::Duration::from_millis(50));

    let mut alice = Device::new("alice2");
    let mut bob = Device::new("bob2");
    relay_client::register(&host_port, &alice.generate_and_register_prekeys().unwrap()).unwrap();
    relay_client::register(&host_port, &bob.generate_and_register_prekeys().unwrap()).unwrap();

    let bob_bundle = relay_client::fetch_bundle(&host_port, "bob2").unwrap();
    alice.establish_session_from_bundle(&bob_bundle).unwrap();

    // alice -> bob (first message, PreKeySignalMessage)
    let (t1, c1) = alice.encrypt("bob2", DEVICE_ID, b"ping").unwrap();
    relay_client::send_ciphertext(&host_port, "bob2", "alice2", DEVICE_ID, t1, &c1, now_millis()).unwrap();
    let mut inbox = relay_client::pull_messages(&host_port, "bob2").unwrap();
    let env = inbox.remove(0);
    let received = bob.decrypt("alice2", env.sender_device_id, env.message_type, &env.ciphertext).unwrap();
    assert_eq!(received, b"ping");

    // bob -> alice (reply; bob's session was established as a side effect
    // of decrypting alice's PreKeySignalMessage — bob never called
    // establish_session_from_bundle himself).
    let (t2, c2) = bob.encrypt("alice2", DEVICE_ID, b"pong").unwrap();
    relay_client::send_ciphertext(&host_port, "alice2", "bob2", DEVICE_ID, t2, &c2, now_millis()).unwrap();
    let mut alice_inbox = relay_client::pull_messages(&host_port, "alice2").unwrap();
    let env2 = alice_inbox.remove(0);
    let received2 = alice.decrypt("bob2", env2.sender_device_id, env2.message_type, &env2.ciphertext).unwrap();
    assert_eq!(received2, b"pong");

    // alice -> bob again, now that alice has heard back: her session is
    // acknowledged, so this one really is a plain Whisper message.
    let (t3, c3) = alice.encrypt("bob2", DEVICE_ID, b"got your pong").unwrap();
    assert_eq!(
        t3,
        libsignal_protocol::CiphertextMessageType::Whisper as u8,
        "after receiving a reply, alice's session should be acknowledged"
    );
    let received3 = bob.decrypt("alice2", DEVICE_ID, t3, &c3).unwrap();
    assert_eq!(received3, b"got your pong");
}

#[test]
fn sending_to_an_unregistered_recipient_fails_cleanly() {
    let (addr, _state) = relay::spawn("127.0.0.1:0").expect("failed to start relay");
    let host_port = addr.to_string();
    std::thread::sleep(std::time::Duration::from_millis(50));

    let bundle_err = relay_client::fetch_bundle(&host_port, "nobody-has-ever-registered-this-name");
    assert!(bundle_err.is_err(), "expected fetching an unregistered user's bundle to fail");
    assert_eq!(bundle_err.unwrap_err().status, 404);

    let send_err = relay_client::send_ciphertext(
        &host_port,
        "also-nobody",
        "alice",
        DEVICE_ID,
        2,
        b"not-real-ciphertext",
        now_millis(),
    );
    assert!(send_err.is_err());
    assert_eq!(send_err.unwrap_err().status, 404);
}
