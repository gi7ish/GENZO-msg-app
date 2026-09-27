//! Minimal relay server for the two-device vertical slice.
//!
//! Responsibilities, and nothing more:
//! 1. Directory: hold each user's *public* identity key + prekey bundle so a
//!    peer can fetch it and run X3DH.
//! 2. Queue: hold encrypted envelopes for a recipient until they poll for
//!    them, then delete them (no permanent server-side message store).
//!
//! What it deliberately does NOT have: a plaintext message table, a
//! database of who-talks-to-whom beyond the transient queue, or any private
//! key material (it only ever sees what devices choose to upload, which is
//! public keys and ciphertext).
//!
//! Transport: a hand-rolled, single-threaded-per-connection HTTP/1.1 subset
//! over `std::net`. See /docs/KNOWN_LIMITATIONS.md for why this isn't axum/
//! tokio — short version: their current releases need a newer rustc than is
//! available in this build environment, and pulling in an old, unmaintained
//! axum/tokio version to dodge that felt like a worse trade than just not
//! depending on a web framework for four routes.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;

use common::{EnvelopeOut, ErrorResponse, PreKeyBundleWire, PullResponse, RegisterRequest, SendEnvelopeRequest, Ack};

#[derive(Default)]
pub struct RelayState {
    directory: Mutex<HashMap<String, DirectoryEntry>>,
    queues: Mutex<HashMap<String, Vec<EnvelopeOut>>>,
}

struct DirectoryEntry {
    device_id: u32,
    registration_id: u32,
    identity_key_b64: String,
    signed_prekey_id: u32,
    signed_prekey_public_b64: String,
    signed_prekey_signature_b64: String,
    /// Consumed (set to `None`) the first time a peer fetches this bundle —
    /// a one-time prekey must never be handed out twice.
    one_time_prekey: Option<(u32, String)>,
    kyber_prekey_id: u32,
    kyber_prekey_public_b64: String,
    kyber_prekey_signature_b64: String,
}

impl RelayState {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    fn register(&self, req: RegisterRequest) {
        let mut dir = self.directory.lock().unwrap();
        dir.insert(
            req.user_id.clone(),
            DirectoryEntry {
                device_id: req.device_id,
                registration_id: req.registration_id,
                identity_key_b64: req.identity_key_b64,
                signed_prekey_id: req.signed_prekey_id,
                signed_prekey_public_b64: req.signed_prekey_public_b64,
                signed_prekey_signature_b64: req.signed_prekey_signature_b64,
                one_time_prekey: Some((req.one_time_prekey_id, req.one_time_prekey_public_b64)),
                kyber_prekey_id: req.kyber_prekey_id,
                kyber_prekey_public_b64: req.kyber_prekey_public_b64,
                kyber_prekey_signature_b64: req.kyber_prekey_signature_b64,
            },
        );
        self.queues.lock().unwrap().entry(req.user_id).or_default();
    }

    fn fetch_bundle(&self, user_id: &str) -> Option<PreKeyBundleWire> {
        let mut dir = self.directory.lock().unwrap();
        let entry = dir.get_mut(user_id)?;
        let one_time = entry.one_time_prekey.take(); // consume: at most once
        Some(PreKeyBundleWire {
            user_id: user_id.to_string(),
            device_id: entry.device_id,
            registration_id: entry.registration_id,
            identity_key_b64: entry.identity_key_b64.clone(),
            signed_prekey_id: entry.signed_prekey_id,
            signed_prekey_public_b64: entry.signed_prekey_public_b64.clone(),
            signed_prekey_signature_b64: entry.signed_prekey_signature_b64.clone(),
            one_time_prekey_id: one_time.as_ref().map(|(id, _)| *id),
            one_time_prekey_public_b64: one_time.map(|(_, k)| k),
            kyber_prekey_id: entry.kyber_prekey_id,
            kyber_prekey_public_b64: entry.kyber_prekey_public_b64.clone(),
            kyber_prekey_signature_b64: entry.kyber_prekey_signature_b64.clone(),
        })
    }

    fn enqueue(&self, recipient_id: &str, envelope: EnvelopeOut) -> bool {
        let mut queues = self.queues.lock().unwrap();
        match queues.get_mut(recipient_id) {
            Some(q) => {
                q.push(envelope);
                true
            }
            None => false, // unknown recipient — not registered
        }
    }

    /// Pull-and-delete: once handed to the recipient, the envelope is gone
    /// from server storage. This is the "no permanent server-side copy"
    /// property from the architecture doc's §7.
    fn drain(&self, user_id: &str) -> Vec<EnvelopeOut> {
        let mut queues = self.queues.lock().unwrap();
        match queues.get_mut(user_id) {
            Some(q) => std::mem::take(q),
            None => Vec::new(),
        }
    }
}

/// Start the relay listening on `addr` (e.g. "127.0.0.1:0" to get an
/// OS-assigned free port) and serve forever on a background thread.
/// Returns the actual bound address.
pub fn spawn(addr: &str) -> std::io::Result<(std::net::SocketAddr, Arc<RelayState>)> {
    let listener = TcpListener::bind(addr)?;
    let bound = listener.local_addr()?;
    let state = RelayState::new();
    let state_for_thread = state.clone();
    thread::spawn(move || {
        for stream in listener.incoming() {
            match stream {
                Ok(stream) => {
                    let state = state_for_thread.clone();
                    thread::spawn(move || {
                        let _ = handle_connection(stream, &state);
                    });
                }
                Err(_) => continue,
            }
        }
    });
    Ok((bound, state))
}

struct ParsedRequest {
    method: String,
    path: String,
    body: Vec<u8>,
}

fn read_request(stream: &TcpStream) -> std::io::Result<Option<ParsedRequest>> {
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut request_line = String::new();
    if reader.read_line(&mut request_line)? == 0 {
        return Ok(None); // client closed without sending anything
    }
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let path = parts.next().unwrap_or("").to_string();

    let mut content_length: usize = 0;
    loop {
        let mut header_line = String::new();
        if reader.read_line(&mut header_line)? == 0 {
            break;
        }
        let trimmed = header_line.trim_end();
        if trimmed.is_empty() {
            break; // end of headers
        }
        if let Some((name, value)) = trimmed.split_once(':') {
            if name.trim().eq_ignore_ascii_case("content-length") {
                content_length = value.trim().parse().unwrap_or(0);
            }
        }
    }

    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        reader.read_exact(&mut body)?;
    }

    Ok(Some(ParsedRequest { method, path, body }))
}

fn write_json_response(stream: &mut TcpStream, status: u16, status_text: &str, body: &str) -> std::io::Result<()> {
    let response = format!(
        "HTTP/1.1 {status} {status_text}\r\nContent-Type: application/json\r\nContent-Length: {len}\r\nConnection: close\r\n\r\n{body}",
        status = status,
        status_text = status_text,
        len = body.as_bytes().len(),
        body = body
    );
    stream.write_all(response.as_bytes())
}

fn handle_connection(mut stream: TcpStream, state: &RelayState) -> std::io::Result<()> {
    let req = match read_request(&stream)? {
        Some(r) => r,
        None => return Ok(()),
    };

    let path_segments: Vec<&str> = req.path.trim_start_matches('/').split('/').collect();

    match (req.method.as_str(), path_segments.as_slice()) {
        ("GET", ["v1", "health"]) => {
            write_json_response(&mut stream, 200, "OK", r#"{"ok":true}"#)?;
        }
        ("POST", ["v1", "register"]) => match serde_json::from_slice::<RegisterRequest>(&req.body) {
            Ok(reg) => {
                state.register(reg);
                let ack = serde_json::to_string(&Ack { ok: true }).unwrap();
                write_json_response(&mut stream, 200, "OK", &ack)?;
            }
            Err(e) => {
                let err = serde_json::to_string(&ErrorResponse { error: e.to_string() }).unwrap();
                write_json_response(&mut stream, 400, "Bad Request", &err)?;
            }
        },
        ("GET", ["v1", "prekey_bundle", user_id]) => match state.fetch_bundle(user_id) {
            Some(bundle) => {
                let body = serde_json::to_string(&bundle).unwrap();
                write_json_response(&mut stream, 200, "OK", &body)?;
            }
            None => {
                let err = serde_json::to_string(&ErrorResponse {
                    error: format!("no such user: {user_id}"),
                })
                .unwrap();
                write_json_response(&mut stream, 404, "Not Found", &err)?;
            }
        },
        ("POST", ["v1", "messages", recipient_id]) => {
            match serde_json::from_slice::<SendEnvelopeRequest>(&req.body) {
                Ok(send_req) => {
                    let envelope = EnvelopeOut {
                        sender_id: send_req.sender_id,
                        sender_device_id: send_req.sender_device_id,
                        message_type: send_req.message_type,
                        ciphertext_b64: send_req.ciphertext_b64,
                        sent_at_unix_ms: send_req.sent_at_unix_ms,
                    };
                    if state.enqueue(recipient_id, envelope) {
                        let ack = serde_json::to_string(&Ack { ok: true }).unwrap();
                        write_json_response(&mut stream, 200, "OK", &ack)?;
                    } else {
                        let err = serde_json::to_string(&ErrorResponse {
                            error: format!("no such recipient: {recipient_id}"),
                        })
                        .unwrap();
                        write_json_response(&mut stream, 404, "Not Found", &err)?;
                    }
                }
                Err(e) => {
                    let err = serde_json::to_string(&ErrorResponse { error: e.to_string() }).unwrap();
                    write_json_response(&mut stream, 400, "Bad Request", &err)?;
                }
            }
        }
        ("GET", ["v1", "messages", user_id]) => {
            let envelopes = state.drain(user_id);
            let body = serde_json::to_string(&PullResponse { envelopes }).unwrap();
            write_json_response(&mut stream, 200, "OK", &body)?;
        }
        _ => {
            let err = serde_json::to_string(&ErrorResponse {
                error: "not found".to_string(),
            })
            .unwrap();
            write_json_response(&mut stream, 404, "Not Found", &err)?;
        }
    }

    Ok(())
}
