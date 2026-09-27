//! Wire-format types shared between `relay` and `device`.
//!
//! Everything that crosses the network here is either a public key, a
//! signature, or an opaque Double-Ratchet ciphertext blob. There is no field
//! anywhere in this file that a relay operator could read to recover message
//! content.

use serde::{Deserialize, Serialize};

/// What a device uploads to the relay's directory at registration time.
/// Only public material and a *single* one-time prekey, for this narrow
/// vertical slice (a real deployment uploads a batch of one-time prekeys
/// and replenishes them as the server consumes them).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegisterRequest {
    pub user_id: String,
    pub device_id: u32,
    pub registration_id: u32,
    pub identity_key_b64: String,

    pub signed_prekey_id: u32,
    pub signed_prekey_public_b64: String,
    pub signed_prekey_signature_b64: String,

    pub one_time_prekey_id: u32,
    pub one_time_prekey_public_b64: String,

    pub kyber_prekey_id: u32,
    pub kyber_prekey_public_b64: String,
    pub kyber_prekey_signature_b64: String,
}

/// What the relay hands back when a peer asks for this user's prekey bundle.
/// The one-time prekey is consumed (removed from the directory) the moment
/// it is handed out, matching real Signal-server behaviour: a one-time
/// prekey must never be reused across two different X3DH sessions.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreKeyBundleWire {
    pub user_id: String,
    pub device_id: u32,
    pub registration_id: u32,
    pub identity_key_b64: String,

    pub signed_prekey_id: u32,
    pub signed_prekey_public_b64: String,
    pub signed_prekey_signature_b64: String,

    /// `None` once the directory has run out of one-time prekeys for this
    /// user (in a real deployment this triggers a low-prekey-count warning
    /// on the owning device so it can top up; in this MVP we just registered
    /// exactly one, so a second session request will legitimately get `None`).
    pub one_time_prekey_id: Option<u32>,
    pub one_time_prekey_public_b64: Option<String>,

    pub kyber_prekey_id: u32,
    pub kyber_prekey_public_b64: String,
    pub kyber_prekey_signature_b64: String,
}

/// An encrypted envelope handed to the relay for delivery. `ciphertext_b64`
/// is the serialized libsignal `CiphertextMessage` — either a
/// `PreKeySignalMessage` (first message of a session) or a `SignalMessage`
/// (every message after). `message_type` tells the recipient which one, so
/// it knows whether it needs to consume its own prekeys to establish the
/// session before decrypting.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SendEnvelopeRequest {
    pub sender_id: String,
    pub sender_device_id: u32,
    /// 3 = PreKeySignalMessage (session-establishing), 2 = SignalMessage (ratchet message)
    pub message_type: u8,
    pub ciphertext_b64: String,
    pub sent_at_unix_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnvelopeOut {
    pub sender_id: String,
    pub sender_device_id: u32,
    pub message_type: u8,
    pub ciphertext_b64: String,
    pub sent_at_unix_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PullResponse {
    pub envelopes: Vec<EnvelopeOut>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorResponse {
    pub error: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Ack {
    pub ok: bool,
}
