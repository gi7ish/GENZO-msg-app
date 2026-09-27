//! One simulated "device" (what would be a single Android install in the
//! real app). Owns its own identity, its own prekeys, and its own
//! in-process libsignal protocol store.
//!
//! Every cryptographic operation in this file is a direct call into the
//! official `libsignal-protocol` crate (pinned at tag v0.73.0 — see the
//! workspace Cargo.toml and /docs/KNOWN_LIMITATIONS.md for why that specific
//! tag). Nothing here implements, re-implements, or "simplifies" any
//! cryptographic primitive.

pub mod net;
pub mod relay_client;

use std::time::SystemTime;

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use rand::Rng;

use common::{PreKeyBundleWire, RegisterRequest};
use libsignal_protocol::{
    kem, message_decrypt, message_encrypt, process_prekey_bundle, CiphertextMessage,
    CiphertextMessageType, DeviceId, GenericSignedPreKey, IdentityKey, IdentityKeyPair,
    IdentityKeyStore, InMemSignalProtocolStore, KeyPair, KyberPreKeyId, KyberPreKeyRecord,
    KyberPreKeyStore, PreKeyBundle, PreKeyId, PreKeyRecord, PreKeySignalMessage, PreKeyStore,
    ProtocolAddress, PublicKey, SignalMessage, SignalProtocolError, SignedPreKeyId,
    SignedPreKeyRecord, SignedPreKeyStore, Timestamp,
};

/// Single device per user for this narrow vertical slice. Multi-device
/// (several `DeviceId`s per user, as real Signal supports) is out of scope
/// here — see /docs/KNOWN_LIMITATIONS.md.
pub const DEVICE_ID: u32 = 1;

pub type DeviceResult<T> = Result<T, SignalProtocolError>;

pub struct Device {
    pub user_id: String,
    pub registration_id: u32,
    store: InMemSignalProtocolStore,
}

fn now() -> SystemTime {
    SystemTime::now()
}

fn now_millis() -> u64 {
    now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("system clock is before 1970")
        .as_millis() as u64
}

impl Device {
    /// Step 1 of the flow: generate identity. This is meant to run once, at
    /// "install time" — an Ed25519/X25519-style Curve25519 identity key
    /// pair (`IdentityKeyPair::generate`) plus a random registration ID.
    pub fn new(user_id: impl Into<String>) -> Self {
        let mut rng = rand::rng();
        let identity = IdentityKeyPair::generate(&mut rng);
        // Real registration IDs are 14-bit values (1..=16383); this matches
        // libsignal's own convention (see rust/protocol/src/state/session.rs
        // usage elsewhere in the tree) rather than a value we invented.
        let registration_id: u32 = rng.random_range(1..16384);
        let store = InMemSignalProtocolStore::new(identity, registration_id)
            .expect("constructing an in-memory store cannot fail");
        Self {
            user_id: user_id.into(),
            registration_id,
            store,
        }
    }

    fn identity_key_pair(&self) -> IdentityKeyPair {
        futures::executor::block_on(self.store.identity_store.get_identity_key_pair())
            .expect("in-memory identity store read cannot fail")
    }

    /// Step 2: generate and register prekeys.
    ///
    /// Generates exactly one signed prekey, one one-time (EC) prekey, and
    /// one Kyber (post-quantum, PQXDH) prekey, saves the private halves into
    /// this device's own protocol store, and returns a `RegisterRequest`
    /// containing only the public halves + signatures — the thing that
    /// actually gets uploaded to the relay's directory.
    ///
    /// A real client generates a *batch* of one-time prekeys (Signal's
    /// clients upload ~100 at a time and replenish as the server reports
    /// the count running low); this MVP generates exactly one, matching the
    /// narrow scope you asked for.
    pub fn generate_and_register_prekeys(&mut self) -> DeviceResult<RegisterRequest> {
        let mut rng = rand::rng();
        let identity_pair = self.identity_key_pair();

        // --- signed EC prekey ---
        let signed_id = SignedPreKeyId::from(1u32);
        let signed_keypair = KeyPair::generate(&mut rng);
        let signed_signature = identity_pair
            .private_key()
            .calculate_signature(&signed_keypair.public_key.serialize(), &mut rng)
            .map_err(|e| SignalProtocolError::InvalidArgument(format!("failed to sign prekey: {e}")))?;
        let signed_record = SignedPreKeyRecord::new(
            signed_id,
            Timestamp::from_epoch_millis(now_millis()),
            &signed_keypair,
            &signed_signature,
        );
        futures::executor::block_on(self.store.save_signed_pre_key(signed_id, &signed_record))?;

        // --- one-time EC prekey ---
        let one_time_id = PreKeyId::from(1u32);
        let one_time_keypair = KeyPair::generate(&mut rng);
        let one_time_record = PreKeyRecord::new(one_time_id, &one_time_keypair);
        futures::executor::block_on(self.store.save_pre_key(one_time_id, &one_time_record))?;

        // --- Kyber (post-quantum) prekey, for PQXDH ---
        let kyber_id = KyberPreKeyId::from(1u32);
        let kyber_record = KyberPreKeyRecord::generate(
            kem::KeyType::Kyber1024,
            kyber_id,
            identity_pair.private_key(),
        )?;
        futures::executor::block_on(self.store.save_kyber_pre_key(kyber_id, &kyber_record))?;

        Ok(RegisterRequest {
            user_id: self.user_id.clone(),
            device_id: DEVICE_ID,
            registration_id: self.registration_id,
            identity_key_b64: B64.encode(identity_pair.identity_key().serialize()),

            signed_prekey_id: u32::from(signed_id),
            signed_prekey_public_b64: B64.encode(signed_keypair.public_key.serialize()),
            signed_prekey_signature_b64: B64.encode(&signed_signature),

            one_time_prekey_id: u32::from(one_time_id),
            one_time_prekey_public_b64: B64.encode(one_time_keypair.public_key.serialize()),

            kyber_prekey_id: u32::from(kyber_id),
            kyber_prekey_public_b64: B64.encode(kyber_record.public_key()?.serialize()),
            kyber_prekey_signature_b64: B64.encode(kyber_record.signature()?),
        })
    }

    /// Steps 3–5: given the peer's prekey bundle (as fetched from the relay
    /// directory), run X3DH (`process_prekey_bundle`) to derive a shared
    /// root key and initialize this side's Double Ratchet state for that
    /// peer. After this call, `encrypt`/`decrypt` work for that peer.
    pub fn establish_session_from_bundle(&mut self, bundle: &PreKeyBundleWire) -> DeviceResult<()> {
        let mut rng = rand::rng();
        let peer_address = ProtocolAddress::new(bundle.user_id.clone(), DeviceId::from(bundle.device_id));

        let identity_key_bytes = B64
            .decode(&bundle.identity_key_b64)
            .map_err(|_| SignalProtocolError::InvalidArgument("bad base64 identity key".into()))?;
        let identity_key = IdentityKey::try_from(identity_key_bytes.as_slice())?;

        let signed_prekey_bytes = B64
            .decode(&bundle.signed_prekey_public_b64)
            .map_err(|_| SignalProtocolError::InvalidArgument("bad base64 signed prekey".into()))?;
        let signed_prekey_public = PublicKey::deserialize(&signed_prekey_bytes)
            .map_err(|_| SignalProtocolError::InvalidArgument("bad signed prekey bytes".into()))?;
        let signed_prekey_signature = B64
            .decode(&bundle.signed_prekey_signature_b64)
            .map_err(|_| SignalProtocolError::InvalidArgument("bad base64 signature".into()))?;

        let one_time_prekey = match (&bundle.one_time_prekey_id, &bundle.one_time_prekey_public_b64) {
            (Some(id), Some(key_b64)) => {
                let key_bytes = B64
                    .decode(key_b64)
                    .map_err(|_| SignalProtocolError::InvalidArgument("bad base64 one-time prekey".into()))?;
                let public = PublicKey::deserialize(&key_bytes)
                    .map_err(|_| SignalProtocolError::InvalidArgument("bad one-time prekey bytes".into()))?;
                Some((PreKeyId::from(*id), public))
            }
            _ => None,
        };

        let mut prekey_bundle = PreKeyBundle::new(
            bundle.registration_id,
            DeviceId::from(bundle.device_id),
            one_time_prekey,
            SignedPreKeyId::from(bundle.signed_prekey_id),
            signed_prekey_public,
            signed_prekey_signature.to_vec(),
            identity_key,
        )?;

        let kyber_public_bytes = B64
            .decode(&bundle.kyber_prekey_public_b64)
            .map_err(|_| SignalProtocolError::InvalidArgument("bad base64 kyber prekey".into()))?;
        let kyber_public = kem::PublicKey::deserialize(&kyber_public_bytes)?;
        let kyber_signature = B64
            .decode(&bundle.kyber_prekey_signature_b64)
            .map_err(|_| SignalProtocolError::InvalidArgument("bad base64 kyber signature".into()))?;
        prekey_bundle = prekey_bundle.with_kyber_pre_key(
            KyberPreKeyId::from(bundle.kyber_prekey_id),
            kyber_public,
            kyber_signature.to_vec(),
        );

        futures::executor::block_on(process_prekey_bundle(
            &peer_address,
            &mut self.store.session_store,
            &mut self.store.identity_store,
            &prekey_bundle,
            now(),
            &mut rng,
        ))
    }

    /// Step 6: encrypt one text message for `peer_id`/`peer_device_id`.
    /// A session must already exist (either because this device called
    /// `establish_session_from_bundle`, or because a previous inbound
    /// message from that peer already established one via
    /// `process_prekey_bundle`-equivalent logic inside `message_decrypt`).
    ///
    /// Returns `(message_type, ciphertext_bytes)` — `message_type` is 3
    /// (`PreKeySignalMessage`) for the first message of a session and 2
    /// (`SignalMessage`) afterwards; the recipient needs to know which one
    /// it's looking at before it can deserialize the bytes.
    pub fn encrypt(&mut self, peer_id: &str, peer_device_id: u32, plaintext: &[u8]) -> DeviceResult<(u8, Vec<u8>)> {
        let peer_address = ProtocolAddress::new(peer_id.to_string(), DeviceId::from(peer_device_id));
        let ciphertext = futures::executor::block_on(message_encrypt(
            plaintext,
            &peer_address,
            &mut self.store.session_store,
            &mut self.store.identity_store,
            now(),
        ))?;
        Ok((ciphertext.message_type() as u8, ciphertext.serialize().to_vec()))
    }

    /// Steps 8–9: decrypt one message from `sender_id`/`sender_device_id`.
    /// If `message_type` is `PreKeySignalMessage` (3), this call itself
    /// completes the responder side of X3DH (consuming this device's own
    /// one-time/signed/Kyber prekeys) before running the Double Ratchet
    /// decrypt — mirroring exactly what a real recipient does on first
    /// contact.
    pub fn decrypt(&mut self, sender_id: &str, sender_device_id: u32, message_type: u8, ciphertext_bytes: &[u8]) -> DeviceResult<Vec<u8>> {
        let mut rng = rand::rng();
        let sender_address = ProtocolAddress::new(sender_id.to_string(), DeviceId::from(sender_device_id));

        let ciphertext = match CiphertextMessageType::try_from(message_type)
            .map_err(|_| SignalProtocolError::InvalidArgument(format!("unknown message_type {message_type}")))?
        {
            CiphertextMessageType::PreKey => {
                CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(ciphertext_bytes)?)
            }
            CiphertextMessageType::Whisper => {
                CiphertextMessage::SignalMessage(SignalMessage::try_from(ciphertext_bytes)?)
            }
            other => {
                return Err(SignalProtocolError::InvalidArgument(format!(
                    "decrypt() only handles PreKey/Whisper messages, got {other:?}"
                )))
            }
        };

        futures::executor::block_on(message_decrypt(
            &ciphertext,
            &sender_address,
            &mut self.store.session_store,
            &mut self.store.identity_store,
            &mut self.store.pre_key_store,
            &self.store.signed_pre_key_store,
            &mut self.store.kyber_pre_key_store,
            &mut rng,
        ))
    }

    /// The raw serialized public identity key, for display/verification UI
    /// (safety-number style comparison) — not used by the crypto path
    /// itself, but exposed so tests / a future UI layer can show it.
    pub fn identity_key_b64(&self) -> String {
        B64.encode(self.identity_key_pair().identity_key().serialize())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Wires two in-process `Device`s together *without* going through the
    /// relay at all (no sockets), by manually shuttling the
    /// RegisterRequest/PreKeyBundleWire/envelope structs in memory. This
    /// isolates "is the cryptography correct" from "does the HTTP plumbing
    /// work" (that's covered separately by the relay-backed integration
    /// test in device/tests/two_device_flow.rs).
    fn bundle_from_registration(reg: &RegisterRequest) -> PreKeyBundleWire {
        PreKeyBundleWire {
            user_id: reg.user_id.clone(),
            device_id: reg.device_id,
            registration_id: reg.registration_id,
            identity_key_b64: reg.identity_key_b64.clone(),
            signed_prekey_id: reg.signed_prekey_id,
            signed_prekey_public_b64: reg.signed_prekey_public_b64.clone(),
            signed_prekey_signature_b64: reg.signed_prekey_signature_b64.clone(),
            one_time_prekey_id: Some(reg.one_time_prekey_id),
            one_time_prekey_public_b64: Some(reg.one_time_prekey_public_b64.clone()),
            kyber_prekey_id: reg.kyber_prekey_id,
            kyber_prekey_public_b64: reg.kyber_prekey_public_b64.clone(),
            kyber_prekey_signature_b64: reg.kyber_prekey_signature_b64.clone(),
        }
    }

    #[test]
    fn identity_generation_produces_distinct_keys_each_time() {
        let a = Device::new("alice");
        let b = Device::new("alice-again");
        assert_ne!(a.identity_key_b64(), b.identity_key_b64());
        assert_ne!(a.registration_id, 0);
        assert!(a.registration_id < 16384);
    }

    #[test]
    fn prekey_generation_produces_a_well_formed_register_request() {
        let mut alice = Device::new("alice");
        let reg = alice.generate_and_register_prekeys().expect("generate prekeys");
        assert_eq!(reg.user_id, "alice");
        assert!(!reg.identity_key_b64.is_empty());
        assert!(!reg.signed_prekey_public_b64.is_empty());
        assert!(!reg.signed_prekey_signature_b64.is_empty());
        assert!(!reg.kyber_prekey_public_b64.is_empty());
    }

    #[test]
    fn session_establishment_and_round_trip_encrypt_decrypt() {
        let mut alice = Device::new("alice");
        let mut bob = Device::new("bob");

        let bob_reg = bob.generate_and_register_prekeys().expect("bob prekeys");
        let bob_bundle = bundle_from_registration(&bob_reg);

        alice
            .establish_session_from_bundle(&bob_bundle)
            .expect("alice establishes session with bob");

        let (msg_type, ciphertext) = alice
            .encrypt("bob", DEVICE_ID, b"hello bob, this is alice")
            .expect("alice encrypts");
        assert_eq!(msg_type, CiphertextMessageType::PreKey as u8, "first message must be a PreKeySignalMessage");

        let plaintext = bob
            .decrypt("alice", DEVICE_ID, msg_type, &ciphertext)
            .expect("bob decrypts");
        assert_eq!(plaintext, b"hello bob, this is alice");
    }

    /// Real Double Ratchet behavior, verified here rather than assumed: the
    /// *sender* keeps wrapping outgoing messages as `PreKeySignalMessage`
    /// until it has received at least one reply proving the recipient
    /// actually established the session (it has no other way to know the
    /// first message got through). So alice's 2nd, 3rd, ... messages are
    /// still type `PreKey` here — that is correct, not a bug. What must
    /// still hold is that each one is independently decryptable and the
    /// ciphertexts differ (the ratchet is genuinely advancing, not reusing
    /// a key).
    #[test]
    fn repeated_sends_before_any_reply_still_ratchet_forward_correctly() {
        let mut alice = Device::new("alice");
        let mut bob = Device::new("bob");
        let bob_bundle = bundle_from_registration(&bob.generate_and_register_prekeys().unwrap());
        alice.establish_session_from_bundle(&bob_bundle).unwrap();

        let (t1, c1) = alice.encrypt("bob", DEVICE_ID, b"first").unwrap();
        let (t2, c2) = alice.encrypt("bob", DEVICE_ID, b"second").unwrap();
        assert_eq!(t1, CiphertextMessageType::PreKey as u8);
        assert_eq!(
            t2,
            CiphertextMessageType::PreKey as u8,
            "sender keeps sending PreKeySignalMessages until it hears back from the recipient"
        );
        assert_ne!(c1, c2, "each message must use a distinct ratchet-derived key, even before any reply");

        assert_eq!(bob.decrypt("alice", DEVICE_ID, t1, &c1).unwrap(), b"first");
        assert_eq!(bob.decrypt("alice", DEVICE_ID, t2, &c2).unwrap(), b"second");
    }

    /// The recipient's side, by contrast, is fully established the instant
    /// it processes that first PreKeySignalMessage — so its own replies go
    /// out as plain SignalMessages (type Whisper) right away.
    #[test]
    fn responders_reply_is_a_plain_ratchet_message_from_the_start() {
        let mut alice = Device::new("alice");
        let mut bob = Device::new("bob");
        let bob_bundle = bundle_from_registration(&bob.generate_and_register_prekeys().unwrap());
        alice.establish_session_from_bundle(&bob_bundle).unwrap();

        let (t1, c1) = alice.encrypt("bob", DEVICE_ID, b"hi bob").unwrap();
        assert_eq!(bob.decrypt("alice", DEVICE_ID, t1, &c1).unwrap(), b"hi bob");

        let (t2, c2) = bob.encrypt("alice", DEVICE_ID, b"hi alice, got it").unwrap();
        assert_eq!(
            t2,
            CiphertextMessageType::Whisper as u8,
            "responder's own messages are plain ratchet messages, not prekey messages"
        );
        assert_eq!(alice.decrypt("bob", DEVICE_ID, t2, &c2).unwrap(), b"hi alice, got it");
    }

    #[test]
    fn decrypting_with_the_wrong_session_fails_instead_of_returning_garbage() {
        let mut alice = Device::new("alice");
        let mut bob = Device::new("bob");
        let mut mallory = Device::new("mallory");

        let bob_bundle = bundle_from_registration(&bob.generate_and_register_prekeys().unwrap());
        alice.establish_session_from_bundle(&bob_bundle).unwrap();
        let (msg_type, ciphertext) = alice.encrypt("bob", DEVICE_ID, b"for bob's eyes only").unwrap();

        // Mallory never ran X3DH with alice and has no session for "alice" —
        // decrypting a PreKeySignalMessage without matching prekeys must
        // fail cleanly, not succeed with corrupted output.
        let result = mallory.decrypt("alice", DEVICE_ID, msg_type, &ciphertext);
        assert!(result.is_err(), "decryption with an unrelated device's store must fail");
    }

    #[test]
    fn tampered_ciphertext_fails_to_decrypt() {
        let mut alice = Device::new("alice");
        let mut bob = Device::new("bob");
        let bob_bundle = bundle_from_registration(&bob.generate_and_register_prekeys().unwrap());
        alice.establish_session_from_bundle(&bob_bundle).unwrap();

        let (msg_type, mut ciphertext) = alice.encrypt("bob", DEVICE_ID, b"do not modify me").unwrap();
        // Flip a bit deep in the ciphertext body (past the fixed-size
        // header) so this is a genuine ciphertext tamper, not just a
        // malformed-header parse failure.
        let flip_at = ciphertext.len() - 1;
        ciphertext[flip_at] ^= 0x01;

        let result = bob.decrypt("alice", DEVICE_ID, msg_type, &ciphertext);
        assert!(result.is_err(), "AEAD authentication must reject tampered ciphertext");
    }
}
