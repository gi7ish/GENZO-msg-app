package com.genzo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.genzo.app.crypto.DeviceIdentity
import com.genzo.app.network.PreKeyBundleWire
import com.genzo.app.network.RegisterRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.message.CiphertextMessage

/**
 * Direct Android counterpart of the Rust vertical slice's device unit
 * tests (vertical-slice/device/src/lib.rs `#[cfg(test)] mod tests`). Same
 * scenarios, same assertions, ported not reinvented, now exercising the
 * real org.signal:libsignal-client/libsignal-android JNI bindings instead
 * of the Rust crate directly.
 *
 * UNVERIFIED — see /docs/ANDROID_BUILD_STATUS.md. This has never been run
 * against a real device/emulator in this sandbox (no Android SDK access).
 */
@RunWith(AndroidJUnit4::class)
class CryptoRoundTripTest {

    private fun bundleFromRegistration(reg: RegisterRequest): PreKeyBundleWire = PreKeyBundleWire(
        user_id = reg.user_id,
        device_id = reg.device_id,
        registration_id = reg.registration_id,
        identity_key_b64 = reg.identity_key_b64,
        signed_prekey_id = reg.signed_prekey_id,
        signed_prekey_public_b64 = reg.signed_prekey_public_b64,
        signed_prekey_signature_b64 = reg.signed_prekey_signature_b64,
        one_time_prekey_id = reg.one_time_prekey_id,
        one_time_prekey_public_b64 = reg.one_time_prekey_public_b64,
        kyber_prekey_id = reg.kyber_prekey_id,
        kyber_prekey_public_b64 = reg.kyber_prekey_public_b64,
        kyber_prekey_signature_b64 = reg.kyber_prekey_signature_b64,
    )

    @Test
    fun identityGeneration_producesDistinctKeysEachTime() {
        val a = DeviceIdentity.create("alice")
        val b = DeviceIdentity.create("alice-again")
        assertNotEquals(a.identityKeyB64(), b.identityKeyB64())
        assertTrue(a.registrationId in 1..16380)
    }

    @Test
    fun prekeyGeneration_producesAWellFormedRegisterRequest() {
        val alice = DeviceIdentity.create("alice")
        val reg = alice.generateAndRegisterPrekeys()
        assertEquals("alice", reg.user_id)
        assertTrue(reg.identity_key_b64.isNotEmpty())
        assertTrue(reg.signed_prekey_public_b64.isNotEmpty())
        assertTrue(reg.signed_prekey_signature_b64.isNotEmpty())
        assertTrue(reg.kyber_prekey_public_b64.isNotEmpty())
    }

    @Test
    fun sessionEstablishment_andRoundTripEncryptDecrypt() {
        val alice = DeviceIdentity.create("alice")
        val bob = DeviceIdentity.create("bob")

        val bobBundle = bundleFromRegistration(bob.generateAndRegisterPrekeys())
        alice.establishSessionFromBundle(bobBundle)

        val (messageType, ciphertext) = alice.encrypt("bob", DeviceIdentity.DEVICE_ID, "hello bob, this is alice".toByteArray())
        assertEquals(
            "first message must be a PreKeySignalMessage",
            CiphertextMessage.PREKEY_TYPE,
            messageType,
        )

        val plaintext = bob.decrypt("alice", DeviceIdentity.DEVICE_ID, messageType, ciphertext)
        assertEquals("hello bob, this is alice", String(plaintext))
    }

    @Test
    fun secondMessage_beforeAnyReply_isStillPreKeyType_butRatchetsForward() {
        val alice = DeviceIdentity.create("alice")
        val bob = DeviceIdentity.create("bob")
        val bobBundle = bundleFromRegistration(bob.generateAndRegisterPrekeys())
        alice.establishSessionFromBundle(bobBundle)

        val (t1, c1) = alice.encrypt("bob", DeviceIdentity.DEVICE_ID, "first".toByteArray())
        val (t2, c2) = alice.encrypt("bob", DeviceIdentity.DEVICE_ID, "second".toByteArray())

        // Real Double Ratchet behavior (verified, not assumed, in the Rust
        // tests too): the *sender* keeps sending PreKeySignalMessages until
        // it has heard back from the recipient.
        assertEquals(CiphertextMessage.PREKEY_TYPE, t1)
        assertEquals(CiphertextMessage.PREKEY_TYPE, t2)
        assertNotEquals("each message must use a distinct ratchet-derived key", c1.toList(), c2.toList())

        assertEquals("first", String(bob.decrypt("alice", DeviceIdentity.DEVICE_ID, t1, c1)))
        assertEquals("second", String(bob.decrypt("alice", DeviceIdentity.DEVICE_ID, t2, c2)))
    }

    @Test
    fun respondersReply_isAPlainRatchetMessageFromTheStart() {
        val alice = DeviceIdentity.create("alice")
        val bob = DeviceIdentity.create("bob")
        val bobBundle = bundleFromRegistration(bob.generateAndRegisterPrekeys())
        alice.establishSessionFromBundle(bobBundle)

        val (t1, c1) = alice.encrypt("bob", DeviceIdentity.DEVICE_ID, "hi bob".toByteArray())
        assertEquals("hi bob", String(bob.decrypt("alice", DeviceIdentity.DEVICE_ID, t1, c1)))

        val (t2, c2) = bob.encrypt("alice", DeviceIdentity.DEVICE_ID, "hi alice, got it".toByteArray())
        assertEquals(
            "responder's own messages are plain ratchet messages, not prekey messages",
            CiphertextMessage.WHISPER_TYPE,
            t2,
        )
        assertEquals("hi alice, got it", String(alice.decrypt("bob", DeviceIdentity.DEVICE_ID, t2, c2)))
    }

    @Test
    fun decryptingWithAnUnrelatedDevicesStore_failsInsteadOfReturningGarbage() {
        val alice = DeviceIdentity.create("alice")
        val bob = DeviceIdentity.create("bob")
        val mallory = DeviceIdentity.create("mallory")

        val bobBundle = bundleFromRegistration(bob.generateAndRegisterPrekeys())
        alice.establishSessionFromBundle(bobBundle)
        val (messageType, ciphertext) = alice.encrypt("bob", DeviceIdentity.DEVICE_ID, "for bob's eyes only".toByteArray())

        // Mallory never ran X3DH with alice and has no session/prekeys for
        // "alice" — decrypting must fail cleanly, not return corrupted output.
        assertThrows(Exception::class.java) {
            mallory.decrypt("alice", DeviceIdentity.DEVICE_ID, messageType, ciphertext)
        }
    }

    @Test
    fun tamperedCiphertext_failsToDecrypt() {
        val alice = DeviceIdentity.create("alice")
        val bob = DeviceIdentity.create("bob")
        val bobBundle = bundleFromRegistration(bob.generateAndRegisterPrekeys())
        alice.establishSessionFromBundle(bobBundle)

        val (messageType, ciphertext) = alice.encrypt("bob", DeviceIdentity.DEVICE_ID, "do not modify me".toByteArray())
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            bob.decrypt("alice", DeviceIdentity.DEVICE_ID, messageType, tampered)
        }
    }
}
