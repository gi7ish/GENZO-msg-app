package com.genzo.app.crypto

import android.util.Base64
import com.genzo.app.network.PreKeyBundleWire
import com.genzo.app.network.RegisterRequest
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.security.SecureRandom

/**
 * One device's identity + protocol session state. Direct Kotlin port of
 * `Device` in vertical-slice/device/src/lib.rs — same method names, same
 * sequencing, same libsignal calls, ported not reimplemented. Every
 * cryptographic operation here is a call into `org.signal:libsignal-client`
 * / `org.signal:libsignal-android` (git tag v0.73.0, matching the Rust
 * side exactly). Nothing in this file implements a cryptographic primitive.
 *
 * KNOWN LIMITATION, carried over unchanged from the Rust reference (see
 * vertical-slice/docs/KNOWN_LIMITATIONS.md, Category B): [store] is
 * in-memory only. Session/ratchet state does not survive a process restart.
 * What *is* durably persisted is the decrypted message content, via
 * [com.genzo.app.data.LocalStore] — exactly the same split as the Rust MVP.
 * Persisting the protocol store itself (encrypted, inside the same
 * SQLCipher database) is the natural next increment, not yet done here.
 */
class DeviceIdentity private constructor(
    val userId: String,
    val registrationId: Int,
    private val store: SignalProtocolStore,
) {
    companion object {
        const val DEVICE_ID = 1

        /** Step 1: generate identity. */
        fun create(userId: String): DeviceIdentity {
            val identity = IdentityKeyPair.generate()
            // 14-bit registration ID (1..16380), matching the convention
            // used elsewhere in libsignal's own tree and in the Rust MVP.
            val registrationId = 1 + SecureRandom().nextInt(16380)
            val store = InMemorySignalProtocolStore(identity, registrationId)
            return DeviceIdentity(userId, registrationId, store)
        }
    }

    private val identityKeyPair: IdentityKeyPair
        get() = store.identityKeyPair

    /** Raw serialized public identity key, base64 — for a future
     * safety-number / device-verification UI. Never expose the private key
     * this way; there is deliberately no accessor for it outside this class. */
    fun identityKeyB64(): String = b64(identityKeyPair.publicKey.serialize())

    /**
     * Step 2: generate + register prekeys. One signed EC prekey, one
     * one-time EC prekey, one Kyber (PQXDH) prekey — same scope as the Rust
     * MVP's `Device::generate_and_register_prekeys` (a real client
     * generates a *batch* of one-time prekeys; this generates one).
     */
    fun generateAndRegisterPrekeys(): RegisterRequest {
        val signedId = 1
        val signedKeyPair: ECKeyPair = Curve.generateKeyPair()
        val signedSignature = identityKeyPair.privateKey.calculateSignature(signedKeyPair.publicKey.serialize())
        val signedRecord = SignedPreKeyRecord(signedId, System.currentTimeMillis(), signedKeyPair, signedSignature)
        store.storeSignedPreKey(signedId, signedRecord)

        val oneTimeId = 1
        val oneTimeKeyPair: ECKeyPair = Curve.generateKeyPair()
        val oneTimeRecord = PreKeyRecord(oneTimeId, oneTimeKeyPair)
        store.storePreKey(oneTimeId, oneTimeRecord)

        val kyberId = 1
        val kyberKeyPair: KEMKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identityKeyPair.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
        val kyberRecord = KyberPreKeyRecord(kyberId, System.currentTimeMillis(), kyberKeyPair, kyberSignature)
        store.storeKyberPreKey(kyberId, kyberRecord)

        return RegisterRequest(
            user_id = userId,
            device_id = DEVICE_ID,
            registration_id = registrationId,
            identity_key_b64 = b64(identityKeyPair.publicKey.serialize()),
            signed_prekey_id = signedId,
            signed_prekey_public_b64 = b64(signedKeyPair.publicKey.serialize()),
            signed_prekey_signature_b64 = b64(signedSignature),
            one_time_prekey_id = oneTimeId,
            one_time_prekey_public_b64 = b64(oneTimeKeyPair.publicKey.serialize()),
            kyber_prekey_id = kyberId,
            kyber_prekey_public_b64 = b64(kyberKeyPair.publicKey.serialize()),
            kyber_prekey_signature_b64 = b64(kyberSignature),
        )
    }

    /**
     * Steps 3–5: given the peer's bundle (as fetched from the relay
     * directory), run X3DH and initialize the Double Ratchet for that peer.
     * Mirrors `Device::establish_session_from_bundle`.
     */
    fun establishSessionFromBundle(bundle: PreKeyBundleWire) {
        val peerAddress = SignalProtocolAddress(bundle.user_id, bundle.device_id)

        val identityKey = IdentityKey(unb64(bundle.identity_key_b64), 0)
        val signedPrekeyPublic = Curve.decodePoint(unb64(bundle.signed_prekey_public_b64), 0)
        val oneTimePrekeyPublic = bundle.one_time_prekey_public_b64?.let { Curve.decodePoint(unb64(it), 0) }
        val kyberPrekeyPublic = KEMPublicKey(unb64(bundle.kyber_prekey_public_b64))

        val preKeyBundle = PreKeyBundle(
            bundle.registration_id,
            bundle.device_id,
            bundle.one_time_prekey_id ?: PreKeyBundle.NULL_PRE_KEY_ID,
            oneTimePrekeyPublic,
            bundle.signed_prekey_id,
            signedPrekeyPublic,
            unb64(bundle.signed_prekey_signature_b64),
            identityKey,
            bundle.kyber_prekey_id,
            kyberPrekeyPublic,
            unb64(bundle.kyber_prekey_signature_b64),
        )

        SessionBuilder(store, peerAddress).process(preKeyBundle)
    }

    /**
     * Step 6: encrypt one message. Mirrors `Device::encrypt`. Returns
     * (messageType, ciphertextBytes) — messageType is
     * `CiphertextMessage.PREKEY_TYPE` (3) for a session's first message,
     * `CiphertextMessage.WHISPER_TYPE` (2) afterwards. Same real-protocol
     * behavior verified in the Rust tests applies here unchanged: the
     * *sender* keeps emitting PreKeySignalMessages until it has received at
     * least one reply proving the recipient actually established the
     * session; the *recipient*'s own replies are plain ratchet messages
     * from the moment it processes that first message.
     */
    fun encrypt(peerId: String, peerDeviceId: Int, plaintext: ByteArray): Pair<Int, ByteArray> {
        val peerAddress = SignalProtocolAddress(peerId, peerDeviceId)
        val cipher = SessionCipher(store, peerAddress)
        val ciphertext: CiphertextMessage = cipher.encrypt(plaintext)
        return Pair(ciphertext.type, ciphertext.serialize())
    }

    /**
     * Steps 8–9: decrypt one message. Mirrors `Device::decrypt`. If
     * `messageType` is PREKEY_TYPE, this call itself completes the
     * responder side of X3DH, consuming this device's own prekeys.
     */
    fun decrypt(senderId: String, senderDeviceId: Int, messageType: Int, ciphertextBytes: ByteArray): ByteArray {
        val senderAddress = SignalProtocolAddress(senderId, senderDeviceId)
        val cipher = SessionCipher(store, senderAddress)
        return when (messageType) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(ciphertextBytes))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(ciphertextBytes))
            else -> throw IllegalArgumentException("decrypt() only handles PreKey/Whisper messages, got $messageType")
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
