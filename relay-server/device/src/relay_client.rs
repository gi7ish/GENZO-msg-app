//! Typed client for talking to the relay's four routes. Thin wrapper over
//! `net`'s raw HTTP calls + `common`'s wire structs.

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;

use crate::net;
use common::{Ack, EnvelopeOut, ErrorResponse, PreKeyBundleWire, PullResponse, RegisterRequest, SendEnvelopeRequest};

#[derive(Debug)]
pub struct RelayClientError {
    pub status: u16,
    pub message: String,
}

impl std::fmt::Display for RelayClientError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "relay returned HTTP {}: {}", self.status, self.message)
    }
}
impl std::error::Error for RelayClientError {}

fn parse_error_body(status: u16, body: &[u8]) -> RelayClientError {
    let message = serde_json::from_slice::<ErrorResponse>(body)
        .map(|e| e.error)
        .unwrap_or_else(|_| String::from_utf8_lossy(body).to_string());
    RelayClientError { status, message }
}

pub fn register(host_port: &str, req: &RegisterRequest) -> Result<(), RelayClientError> {
    let body = serde_json::to_string(req).expect("RegisterRequest always serializes");
    let resp = net::post_json(host_port, "/v1/register", &body)
        .map_err(|e| RelayClientError { status: 0, message: e.to_string() })?;
    if resp.status != 200 {
        return Err(parse_error_body(resp.status, &resp.body));
    }
    let _ack: Ack = serde_json::from_slice(&resp.body).expect("register ack should be valid JSON");
    Ok(())
}

pub fn fetch_bundle(host_port: &str, user_id: &str) -> Result<PreKeyBundleWire, RelayClientError> {
    let resp = net::get(host_port, &format!("/v1/prekey_bundle/{user_id}"))
        .map_err(|e| RelayClientError { status: 0, message: e.to_string() })?;
    if resp.status != 200 {
        return Err(parse_error_body(resp.status, &resp.body));
    }
    Ok(serde_json::from_slice(&resp.body).expect("bundle response should be valid JSON"))
}

pub fn send_ciphertext(
    host_port: &str,
    recipient_id: &str,
    sender_id: &str,
    sender_device_id: u32,
    message_type: u8,
    ciphertext: &[u8],
    sent_at_unix_ms: u64,
) -> Result<(), RelayClientError> {
    let req = SendEnvelopeRequest {
        sender_id: sender_id.to_string(),
        sender_device_id,
        message_type,
        ciphertext_b64: B64.encode(ciphertext),
        sent_at_unix_ms,
    };
    let body = serde_json::to_string(&req).expect("SendEnvelopeRequest always serializes");
    let resp = net::post_json(host_port, &format!("/v1/messages/{recipient_id}"), &body)
        .map_err(|e| RelayClientError { status: 0, message: e.to_string() })?;
    if resp.status != 200 {
        return Err(parse_error_body(resp.status, &resp.body));
    }
    Ok(())
}

pub struct DecodedEnvelope {
    pub sender_id: String,
    pub sender_device_id: u32,
    pub message_type: u8,
    pub ciphertext: Vec<u8>,
}

pub fn pull_messages(host_port: &str, user_id: &str) -> Result<Vec<DecodedEnvelope>, RelayClientError> {
    let resp = net::get(host_port, &format!("/v1/messages/{user_id}"))
        .map_err(|e| RelayClientError { status: 0, message: e.to_string() })?;
    if resp.status != 200 {
        return Err(parse_error_body(resp.status, &resp.body));
    }
    let parsed: PullResponse = serde_json::from_slice(&resp.body).expect("pull response should be valid JSON");
    Ok(parsed
        .envelopes
        .into_iter()
        .map(|e: EnvelopeOut| DecodedEnvelope {
            sender_id: e.sender_id,
            sender_device_id: e.sender_device_id,
            message_type: e.message_type,
            ciphertext: B64.decode(&e.ciphertext_b64).expect("relay should only ever hand back valid base64"),
        })
        .collect())
}

/// Poll `fetch_bundle` until the peer has registered (or `max_attempts` is
/// hit). Real clients don't need this — a real recipient registers once at
/// install time, long before anyone tries to message them — but it makes
/// the two-process demo (`device_a`/`device_b` binaries) robust to whichever
/// process happens to start first.
pub fn wait_for_bundle(host_port: &str, user_id: &str, max_attempts: u32) -> Result<PreKeyBundleWire, RelayClientError> {
    let mut last_err = None;
    for _ in 0..max_attempts {
        match fetch_bundle(host_port, user_id) {
            Ok(bundle) => return Ok(bundle),
            Err(e) => {
                last_err = Some(e);
                std::thread::sleep(std::time::Duration::from_millis(300));
            }
        }
    }
    Err(last_err.unwrap_or(RelayClientError { status: 0, message: "timed out".into() }))
}

/// Poll `pull_messages` until at least one envelope arrives (or times out).
pub fn wait_for_message(host_port: &str, user_id: &str, max_attempts: u32) -> Result<DecodedEnvelope, RelayClientError> {
    for _ in 0..max_attempts {
        let mut msgs = pull_messages(host_port, user_id)?;
        if !msgs.is_empty() {
            return Ok(msgs.remove(0));
        }
        std::thread::sleep(std::time::Duration::from_millis(300));
    }
    Err(RelayClientError { status: 0, message: "timed out waiting for a message".into() })
}
