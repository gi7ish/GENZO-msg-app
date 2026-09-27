package com.genzo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.genzo.app.data.KeystoreKeyProvider
import com.genzo.app.data.LocalStore
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * Direct Android counterpart of local_store's Rust tests
 * (vertical-slice/local_store/src/lib.rs `#[cfg(test)] mod tests`).
 *
 * UNVERIFIED — see /docs/ANDROID_BUILD_STATUS.md.
 */
@RunWith(AndroidJUnit4::class)
class LocalStoreTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun randomKeyHex(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun freshDbName(tag: String) = "genzo_test_${tag}_${System.nanoTime()}.db"

    @Test
    fun writeAndReadBackAMessage() {
        val dbFile = context.getDatabasePath(freshDbName("rw"))
        try {
            val store = LocalStore(context, randomKeyHex(), dbFile.name)
            store.insertMessage("conv:alice:bob", "alice", LocalStore.Direction.OUTGOING, "hi bob", 1000L)
            val messages = store.messagesForConversation("conv:alice:bob")
            assertEquals(1, messages.size)
            assertEquals("hi bob", messages[0].plaintextBody)
            assertEquals("alice", messages[0].senderId)
            assertEquals("outgoing", messages[0].direction)
            store.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun databaseIsUnreadableWithoutTheCorrectKey() {
        val dbFile = context.getDatabasePath(freshDbName("locked"))
        try {
            val store = LocalStore(context, randomKeyHex(), dbFile.name)
            store.insertMessage("conv:x", "alice", LocalStore.Direction.OUTGOING, "secret content", 1L)
            store.close()

            // Re-open the same file with a different (wrong) key.
            assertThrows(Exception::class.java) {
                val wrongKeyDb = SQLiteDatabase.openOrCreateDatabase(dbFile, "x'${randomKeyHex()}'", null, null)
                wrongKeyDb.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun rawFileBytesDoNotContainThePlaintext() {
        val dbFile = context.getDatabasePath(freshDbName("rawbytes"))
        val needle = "PLAINTEXT_CANARY_STRING_should_not_appear_on_disk"
        try {
            val store = LocalStore(context, randomKeyHex(), dbFile.name)
            store.insertMessage("conv:y", "bob", LocalStore.Direction.INCOMING, needle, 1L)
            store.close()

            val raw = dbFile.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(
                "found plaintext message content in the raw, at-rest database file",
                raw.contains(needle),
            )
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun keystoreKeyProvider_returnsTheSameKeyAcrossCalls() {
        // Simulates "app restarted" by constructing a fresh provider
        // instance against the same Keystore alias + wrapped-key file.
        val provider1 = KeystoreKeyProvider(context)
        val key1 = provider1.getOrCreateDatabaseKeyHex()

        val provider2 = KeystoreKeyProvider(context)
        val key2 = provider2.getOrCreateDatabaseKeyHex()

        assertEquals("the same wrapped key must unwrap to the same value on a fresh provider instance", key1, key2)
        assertTrue(key1.length == 64) // 32 bytes, hex-encoded
    }
}
