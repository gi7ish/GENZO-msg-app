package com.genzo.app

import com.genzo.app.network.PreKeyBundleWire
import com.genzo.app.network.RegisterRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one part of this project that's a plain JVM test rather than an
 * instrumented one: JSON (de)serialization needs no Android runtime, no
 * libsignal native library, no SQLCipher. Run via `./gradlew test`, not
 * `./gradlew connectedAndroidTest`. See CryptoRoundTripTest/LocalStoreTest
 * for the tests that DO need a device/emulator.
 */
class WireModelsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun registerRequest_roundTripsThroughJson() {
        val req = RegisterRequest(
            user_id = "alice",
            device_id = 1,
            registration_id = 1234,
            identity_key_b64 = "AAAA",
            signed_prekey_id = 1,
            signed_prekey_public_b64 = "BBBB",
            signed_prekey_signature_b64 = "CCCC",
            one_time_prekey_id = 1,
            one_time_prekey_public_b64 = "DDDD",
            kyber_prekey_id = 1,
            kyber_prekey_public_b64 = "EEEE",
            kyber_prekey_signature_b64 = "FFFF",
        )
        val encoded = json.encodeToString(req)
        val decoded = json.decodeFromString(RegisterRequest.serializer(), encoded)
        assertEquals(req, decoded)
    }

    @Test
    fun preKeyBundleWire_withNoOneTimePrekey_decodesNullsCorrectly() {
        // Matches the shape the relay sends once a user's one-time prekey
        // has already been consumed by an earlier fetch — see
        // relay/src/lib.rs's fetch_bundle / DirectoryEntry.one_time_prekey.
        val jsonText = """
            {
              "user_id": "bob", "device_id": 1, "registration_id": 42,
              "identity_key_b64": "AAAA",
              "signed_prekey_id": 1, "signed_prekey_public_b64": "BBBB", "signed_prekey_signature_b64": "CCCC",
              "kyber_prekey_id": 1, "kyber_prekey_public_b64": "EEEE", "kyber_prekey_signature_b64": "FFFF"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(PreKeyBundleWire.serializer(), jsonText)
        assertEquals("bob", decoded.user_id)
        assertNull(decoded.one_time_prekey_id)
        assertNull(decoded.one_time_prekey_public_b64)
    }
}
