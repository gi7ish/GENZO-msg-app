// Android reference sketch — see android_reference/README.md before reading
// this. NOT COMPILED, NOT TESTED. Mirrors device/src/lib.rs's proven logic
// 1:1, method for method, using the real libsignal-client Java/Kotlin API
// verified from the actual source of tag v0.73.0 of signalapp/libsignal
// (java/shared/java/org/signal/libsignal/protocol/{SessionBuilder,
// SessionCipher,state/PreKeyBundle,state/SignalProtocolStore,
// state/impl/InMemorySignalProtocolStore}.java).
//
// Gradle dependency this assumes (NOT verified to resolve/build here —
// Maven access is unavailable in this sandbox):
//   implementation("org.signal:libsignal-client:0.73.0")

package com.genzo.core.crypto

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
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
 * One simulated "device" — direct Kotlin mirror of `device::Device` in the
 * Rust crate. Single device per user (device ID 1), matching the same
 * narrow-scope decision documented in /docs/KNOWN_LIMITATIONS.md.
 *
 * PRODUCTION WARNING: this uses [InMemorySignalProtocolStore], which really
 * does exist in libsignal-client's shared module — but as its name says,
 * it is in-memory only. A real Android build MUST replace this with a
 * [SignalProtocolStore] implementation backed by:
 *   - Android Keystore (hardware-backed where available) for the identity
 *     key pair and prekey private material, and
 *   - an SQLCipher-encrypted SQLite database for session/ratchet state,
 * exactly as specified in the original architecture document's Key
 * Management table. Nothing below does that; see the TODOs.
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
            // Real registration IDs are 14-bit values (1..16380), matching
            // the convention used elsewhere in libsignal's own tree.
            val registrationId = 1 + SecureRandom().nextInt(16380)

            // TODO(production): replace with a store backed by Android
            // Keystore + SQLCipher. InMemorySignalProtocolStore is used
            // here only because it requires zero extra plumbing to mirror
            // the Rust proof-of-concept's flow.
            val store = InMemorySignalProtocolStore(identity, registrationId)

            return DeviceIdentity(userId, registrationId, store)
        }
    }

    val identityKeyPair: IdentityKeyPair
        get() = store.identityKeyPair

    /**
     * Step 2: generate + register prekeys. Mirrors
     * `Device::generate_and_register_prekeys` in the Rust crate exactly:
     * one signed EC prekey, one one-time EC prekey, one Kyber (PQXDH)
     * prekey. A real client generates a *batch* of one-time prekeys; this
     * generates exactly one, matching the Rust MVP's scope.
     */
    fun generateAndRegisterPrekeys(): RegisterRequest {
        val signedId = 1
        val signedKeyPair: ECKeyPair = Curve.generateKeyPair()
        val signedSignature = identityKeyPair.privateKey
            .calculateSignature(signedKeyPair.publicKey.serialize())
        val signedRecord = SignedPreKeyRecord(
            signedId,
            System.currentTimeMillis(),
            signedKeyPair,
            signedSignature,
        )
        store.storeSignedPreKey(signedId, signedRecord)

        val oneTimeId = 1
        val oneTimeKeyPair: ECKeyPair = Curve.generateKeyPair()
        val oneTimeRecord = PreKeyRecord(oneTimeId, oneTimeKeyPair)
        store.storePreKey(oneTimeId, oneTimeRecord)

        val kyberId = 1
        val kyberKeyPair: KEMKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identityKeyPair.privateKey
            .calculateSignature(kyberKeyPair.publicKey.serialize())
        val kyberRecord = KyberPreKeyRecord(
            kyberId,
            System.currentTimeMillis(),
            kyberKeyPair,
            kyberSignature,
        )
        store.storeKyberPreKey(kyberId, kyberRecord)

        return RegisterRequest(
            userId = userId,
            deviceId = DEVICE_ID,
            registrationId = registrationId,
            identityKeyB64 = b64(identityKeyPair.publicKey.serialize()),
            signedPrekeyId = signedId,
            signedPrekeyPublicB64 = b64(signedKeyPair.publicKey.serialize()),
            signedPrekeySignatureB64 = b64(signedSignature),
            oneTimePrekeyId = oneTimeId,
            oneTimePrekeyPublicB64 = b64(oneTimeKeyPair.publicKey.serialize()),
            kyberPrekeyId = kyberId,
            kyberPrekeyPublicB64 = b64(kyberKeyPair.publicKey.serialize()),
            kyberPrekeySignatureB64 = b64(kyberSignature),
        )
    }

    /**
     * Steps 3–5: given the peer's fetched bundle, run X3DH and initialize
     * the Double Ratchet. Mirrors `Device::establish_session_from_bundle`.
     */
    @Throws(InvalidKeyException::class)
    fun establishSessionFromBundle(bundle: PreKeyBundleWire) {
        val peerAddress = SignalProtocolAddress(bundle.userId, bundle.deviceId)

        val identityKey = IdentityKey(unb64(bundle.identityKeyB64), 0)
        val signedPrekeyPublic = Curve.decodePoint(unb64(bundle.signedPrekeyPublicB64), 0)
        val oneTimePrekeyPublic = bundle.oneTimePrekeyPublicB64?.let { Curve.decodePoint(unb64(it), 0) }
        val kyberPrekeyPublic = org.signal.libsignal.protocol.kem.KEMPublicKey(unb64(bundle.kyberPrekeyPublicB64))

        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            bundle.oneTimePrekeyId ?: -1,
            oneTimePrekeyPublic,
            bundle.signedPrekeyId,
            signedPrekeyPublic,
            unb64(bundle.signedPrekeySignatureB64),
            identityKey,
            bundle.kyberPrekeyId,
            kyberPrekeyPublic,
            unb64(bundle.kyberPrekeySignatureB64),
        )

        SessionBuilder(store, peerAddress).process(preKeyBundle)
    }

    /**
     * Step 6: encrypt one message. Mirrors `Device::encrypt`. Returns
     * (messageType, ciphertextBytes) exactly like the Rust version — 3 for
     * PreKeySignalMessage, 2 for SignalMessage — since the recipient needs
     * to know which one it's looking at before deserializing.
     */
    fun encrypt(peerId: String, peerDeviceId: Int, plaintext: ByteArray): Pair<Int, ByteArray> {
        val peerAddress = SignalProtocolAddress(peerId, peerDeviceId)
        val cipher = SessionCipher(store, peerAddress)
        val ciphertext: CiphertextMessage = cipher.encrypt(plaintext)
        return Pair(ciphertext.type, ciphertext.serialize())
    }

    /**
     * Steps 8–9: decrypt one message. Mirrors `Device::decrypt`. If
     * `messageType` is PREKEY_TYPE (3), this call itself completes the
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

    private fun b64(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
}

/** Wire DTOs — kept identical in shape to the Rust `common` crate's structs
 * so the same relay (or a Kotlin reimplementation of it) can serve either
 * client without protocol changes. */
data class RegisterRequest(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKeyB64: String,
    val signedPrekeyId: Int,
    val signedPrekeyPublicB64: String,
    val signedPrekeySignatureB64: String,
    val oneTimePrekeyId: Int,
    val oneTimePrekeyPublicB64: String,
    val kyberPrekeyId: Int,
    val kyberPrekeyPublicB64: String,
    val kyberPrekeySignatureB64: String,
)

data class PreKeyBundleWire(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKeyB64: String,
    val signedPrekeyId: Int,
    val signedPrekeyPublicB64: String,
    val signedPrekeySignatureB64: String,
    val oneTimePrekeyId: Int?,
    val oneTimePrekeyPublicB64: String?,
    val kyberPrekeyId: Int,
    val kyberPrekeyPublicB64: String,
    val kyberPrekeySignatureB64: String,
)
