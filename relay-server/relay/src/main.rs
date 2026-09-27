use std::io::{self, Write};

fn main() -> io::Result<()> {
    let addr = std::env::var("RELAY_ADDR").unwrap_or_else(|_| "127.0.0.1:8765".to_string());
    let (bound, _state) = relay::spawn(&addr)?;
    println!("privacy-messenger relay listening on http://{bound}");
    println!("routes: POST /v1/register  GET /v1/prekey_bundle/:user_id  POST /v1/messages/:user_id  GET /v1/messages/:user_id  GET /v1/health");
    io::stdout().flush()?;

    // Block forever; spawn() already runs the accept loop on a background thread.
    loop {
        std::thread::park();
    }
}
