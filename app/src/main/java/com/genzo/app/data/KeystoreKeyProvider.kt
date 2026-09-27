package com.genzo.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects the SQLCipher database passphrase using a hardware-backed
 * (where available) AES-256-GCM key that lives ONLY inside Android
 * Keystore and is never exportable.
 *
 * IMPORTANT — what this does and doesn't do (see the architecture
 * discussion in this step's chat response, and
 * /docs/ANDROID_BUILD_STATUS.md): Android Keystore cannot hold libsignal's
 * Curve25519 identity/session keys directly and still let libsignal's Rust
 * math operate on them — Keystore keys are non-extractable and only
 * support Keystore's own fixed operation set. So this class does NOT
 * protect the Signal protocol identity key (which lives in the in-memory
 * `SignalProtocolStore` — see DeviceIdentity's doc comment on that
 * limitation). What it DOES protect: the passphrase that unlocks the
 * SQLCipher database holding decrypted message history at rest.
 *
 * What's persisted to a plain file ([WRAPPED_KEY_FILE], under the app's
 * private internal storage, never SharedPreferences, never external
 * storage) is the *wrapped* (AES-GCM-ciphertext) form of the database key,
 * never the raw key. That ciphertext is useless without the Keystore
 * key, which never leaves hardware/TEE and cannot be extracted even with
 * root access to the file system — only used in place, via the Keystore
 * API, by whichever app process holds the matching Keystore alias.
 */
class KeystoreKeyProvider(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "genzo_db_key_wrapper_v1"
        private const val WRAPPED_KEY_FILE = "genzo_wrapped_db_key.bin"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val DB_KEY_BYTES = 32 // 256-bit SQLCipher raw key
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Returns the 64-hex-char SQLCipher key (see LocalStore.open's `keyHex`
     * parameter), generating and wrapping a new random one on first call,
     * or unwrapping the previously-generated one on subsequent calls.
     */
    fun getOrCreateDatabaseKeyHex(): String {
        val wrappedFile = File(context.filesDir, WRAPPED_KEY_FILE)
        val rawKeyBytes: ByteArray = if (wrappedFile.exists()) {
            unwrap(wrappedFile.readBytes())
        } else {
            val newKey = ByteArray(DB_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            wrappedFile.writeBytes(wrap(newKey))
            newKey
        }
        return rawKeyBytes.joinToString("") { "%02x".format(it) }
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Deliberately NOT setUserAuthenticationRequired(true) for this
            // MVP — see /docs/ANDROID_BUILD_STATUS.md "what remains
            // untested/unhardened": production hardening per the original
            // architecture doc calls for gating this behind biometric/
            // device-credential auth, which changes the unlock flow (the
            // app must handle a BiometricPrompt/CryptoObject dance around
            // every getOrCreateDatabaseKeyHex() call) and needs real-device
            // testing this sandbox cannot do.

        try {
            specBuilder.setIsStrongBoxBacked(true)
            keyGenerator.init(specBuilder.build())
            return keyGenerator.generateKey()
        } catch (_: Exception) {
            // StrongBox isn't available on this device/emulator (common —
            // most emulators have no StrongBox HAL). Fall back to the
            // normal hardware-backed keystore, which is still real Keystore
            // protection, just without the discrete secure element.
        }
        specBuilder.setIsStrongBoxBacked(false)
        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    private fun wrap(plainKeyBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val ciphertext = cipher.doFinal(plainKeyBytes)
        // Store IV || ciphertext — GCM IVs must never repeat under the same
        // key; a fresh random IV is generated by the Cipher on every
        // ENCRYPT_MODE init above, which we prepend so unwrap() can read it.
        return cipher.iv + ciphertext
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        val iv = wrapped.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = wrapped.copyOfRange(GCM_IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
