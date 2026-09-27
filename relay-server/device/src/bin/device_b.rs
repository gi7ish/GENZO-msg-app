//! Run me as: `RELAY_ADDR=127.0.0.1:8765 cargo run -p device --bin device_b`
//! (in a separate terminal from `cargo run -p relay` and `device_a`).

use device::{relay_client, Device, DEVICE_ID};
use local_store::{Direction, LocalStore};

fn relay_addr() -> String {
    std::env::var("RELAY_ADDR").unwrap_or_else(|_| "127.0.0.1:8765".to_string())
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

fn random_hex_key() -> String {
    use std::io::Read;
    let mut f = std::fs::File::open("/dev/urandom").expect("read OS randomness for the demo DB key");
    let mut buf = [0u8; 32];
    f.read_exact(&mut buf).unwrap();
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

fn main() {
    let addr = relay_addr();
    println!("[device B / bob] using relay at {addr}");

    println!("[device B] step 1: generating identity...");
    let mut me = Device::new("bob");
    println!("[device B]   identity_key (b64, first 24 chars) = {}...", &me.identity_key_b64()[..24]);

    println!("[device B] step 2: generating + registering prekeys...");
    let reg = me.generate_and_register_prekeys().expect("prekey generation");
    relay_client::register(&addr, &reg).expect("register with relay");
    println!("[device B]   registered as 'bob' with the relay directory");

    println!("[device B] step 8: waiting for a message from alice...");
    let envelope = relay_client::wait_for_message(&addr, "bob", 200).expect("no message from alice in time");
    println!(
        "[device B]   received {} bytes from {} (message_type={})",
        envelope.ciphertext.len(),
        envelope.sender_id,
        envelope.message_type
    );

    println!("[device B] step 9: decrypting (this also completes X3DH on bob's side, consuming his prekeys)...");
    let plaintext = me
        .decrypt(&envelope.sender_id, envelope.sender_device_id, envelope.message_type, &envelope.ciphertext)
        .expect("decrypt alice's message");
    let plaintext_str = String::from_utf8_lossy(&plaintext).to_string();
    println!("[device B]   decrypted: {plaintext_str:?}");

    println!("[device B] step 10: storing the message in a local SQLCipher-encrypted database...");
    let db_path = format!("{}/genzo_device_b_messages.db", std::env::temp_dir().display());
    let db_key = random_hex_key(); // stand-in for an Android-Keystore-held key; see local_store::LocalStore::open docs
    let store = LocalStore::open(&db_path, &db_key).expect("open local store");
    store
        .insert_message("conv:alice:bob", &envelope.sender_id, Direction::Incoming, &plaintext_str, now_millis() as i64)
        .expect("persist message");
    println!("[device B]   stored at {db_path}");
    println!(
        "[device B]   ({} message(s) now in this conversation locally)",
        store.message_count("conv:alice:bob").unwrap_or(-1)
    );

    let reply = b"Got it, thanks Alice! - Bob";
    println!("[device B] replying: {:?}", String::from_utf8_lossy(reply));
    let (message_type, ciphertext) = me.encrypt(&envelope.sender_id, envelope.sender_device_id, reply).expect("encrypt reply");
    relay_client::send_ciphertext(&addr, &envelope.sender_id, "bob", DEVICE_ID, message_type, &ciphertext, now_millis())
        .expect("send reply");
    println!("[device B]   reply sent. done.");
}
