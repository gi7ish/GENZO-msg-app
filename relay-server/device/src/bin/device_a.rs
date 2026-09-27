//! Run me as: `RELAY_ADDR=127.0.0.1:8765 cargo run -p device --bin device_a`
//! (in a separate terminal from `cargo run -p relay` and `device_b`).
//!
//! This is a literal second OS process, talking to the relay over a real
//! TCP socket exactly like an Android client would — nothing here is
//! in-process/simulated the way the unit tests are.

use device::{relay_client, Device, DEVICE_ID};

fn relay_addr() -> String {
    std::env::var("RELAY_ADDR").unwrap_or_else(|_| "127.0.0.1:8765".to_string())
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

fn main() {
    let addr = relay_addr();
    println!("[device A / alice] using relay at {addr}");

    println!("[device A] step 1: generating identity...");
    let mut me = Device::new("alice");
    println!("[device A]   identity_key (b64, first 24 chars) = {}...", &me.identity_key_b64()[..24]);
    println!("[device A]   registration_id = {}", me.registration_id);

    println!("[device A] step 2: generating + registering prekeys...");
    let reg = me.generate_and_register_prekeys().expect("prekey generation");
    relay_client::register(&addr, &reg).expect("register with relay");
    println!("[device A]   registered as 'alice' with the relay directory");

    println!("[device A] step 3: waiting for bob to register, then fetching his prekey bundle...");
    let bundle = relay_client::wait_for_bundle(&addr, "bob", 100).expect("bob never registered (start device_b?)");
    println!("[device A]   got bob's bundle (registration_id={})", bundle.registration_id);

    println!("[device A] steps 4-5: running X3DH + initializing the Double Ratchet...");
    me.establish_session_from_bundle(&bundle).expect("establish session");
    println!("[device A]   session established");

    let message = b"Hello Bob, this is Alice. This message is end-to-end encrypted!";
    println!("[device A] step 6: encrypting: {:?}", String::from_utf8_lossy(message));
    let (message_type, ciphertext) = me.encrypt("bob", DEVICE_ID, message).expect("encrypt");
    println!(
        "[device A]   ciphertext = {} bytes, message_type = {} ({})",
        ciphertext.len(),
        message_type,
        if message_type == 3 { "PreKeySignalMessage" } else { "SignalMessage" }
    );

    println!("[device A] step 7: sending ciphertext through the relay...");
    relay_client::send_ciphertext(&addr, "bob", "alice", DEVICE_ID, message_type, &ciphertext, now_millis())
        .expect("send");
    println!("[device A]   sent. waiting for bob's reply...");

    let reply = relay_client::wait_for_message(&addr, "alice", 200).expect("no reply from bob in time");
    let plaintext = me
        .decrypt("bob", reply.sender_device_id, reply.message_type, &reply.ciphertext)
        .expect("decrypt bob's reply");
    println!("[device A] received + decrypted bob's reply: {:?}", String::from_utf8_lossy(&plaintext));
    println!("[device A] done.");
}
