package com.genzo.app.data

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/**
 * Local encrypted message store. Same schema, same column names, and the
 * same reasoning as `local_store::LocalStore` in the Rust vertical slice:
 * the `plaintext_body` column is NOT an optional cache — Double Ratchet
 * forward secrecy deletes a message key the instant it's used, so this is
 * the only durable copy of a message that will ever exist on this device.
 * We protect it (SQLCipher encryption at rest, key supplied by
 * [KeystoreKeyProvider], never hold the raw key longer than needed to open
 * the DB connection) rather than pretend it can be eliminated.
 *
 * VERIFICATION NOTE (see /docs/ANDROID_BUILD_STATUS.md): the exact
 * `SQLiteDatabase.openOrCreateDatabase(...)` overload and raw-key string
 * format (`"x'<64 hex chars>'"`) below follow SQLCipher's long-standing,
 * widely-documented cross-binding convention for supplying a pre-derived
 * raw key rather than a passphrase subject to PBKDF2 — the same convention
 * used in `local_store`'s Rust code via `PRAGMA key = "x'...'"`. This
 * sandbox has no Maven access to fetch net.zetetic:sqlcipher-android's
 * actual javadoc/source to double-check the Kotlin call signature the way
 * Step 8 double-checked libsignal's Java source — confirm this compiles
 * against whatever version Android Studio resolves before trusting it.
 */
class LocalStore(context: Context, keyHex: String, dbName: String = DB_NAME) :
    SQLiteOpenHelper(context, dbName, "x'$keyHex'", null, DB_VERSION, 0, null, null, true) {

    companion object {
        private const val DB_NAME = "genzo_messages.db"
        private const val DB_VERSION = 1

        init {
            System.loadLibrary("sqlcipher")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                sender_id TEXT NOT NULL,
                direction TEXT NOT NULL CHECK (direction IN ('outgoing','incoming')),
                plaintext_body TEXT NOT NULL,
                sent_at_unix_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, sent_at_unix_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No schema migrations yet — v1 is the only version that has ever shipped.
    }

    enum class Direction(val dbValue: String) { OUTGOING("outgoing"), INCOMING("incoming") }

    data class StoredMessage(
        val id: Long,
        val conversationId: String,
        val senderId: String,
        val direction: String,
        val plaintextBody: String,
        val sentAtUnixMs: Long,
    )

    fun insertMessage(
        conversationId: String,
        senderId: String,
        direction: Direction,
        plaintextBody: String,
        sentAtUnixMs: Long,
    ): Long {
        val values = android.content.ContentValues().apply {
            put("conversation_id", conversationId)
            put("sender_id", senderId)
            put("direction", direction.dbValue)
            put("plaintext_body", plaintextBody)
            put("sent_at_unix_ms", sentAtUnixMs)
        }
        return writableDatabase.insert("messages", null, values)
    }

    fun messagesForConversation(conversationId: String): List<StoredMessage> {
        val results = mutableListOf<StoredMessage>()
        readableDatabase.rawQuery(
            """SELECT id, conversation_id, sender_id, direction, plaintext_body, sent_at_unix_ms
               FROM messages WHERE conversation_id = ? ORDER BY sent_at_unix_ms ASC, id ASC""",
            arrayOf(conversationId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results += StoredMessage(
                    id = cursor.getLong(0),
                    conversationId = cursor.getString(1),
                    senderId = cursor.getString(2),
                    direction = cursor.getString(3),
                    plaintextBody = cursor.getString(4),
                    sentAtUnixMs = cursor.getLong(5),
                )
            }
        }
        return results
    }

    fun messageCount(conversationId: String): Long {
        readableDatabase.rawQuery(
            "SELECT count(*) FROM messages WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Every distinct conversation with at least one stored message, most
     * recently active first — backs the conversation-list screen. */
    fun allConversationIds(): List<String> {
        val results = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT conversation_id, MAX(sent_at_unix_ms) as last FROM messages GROUP BY conversation_id ORDER BY last DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) results += cursor.getString(0)
        }
        return results
    }
}
