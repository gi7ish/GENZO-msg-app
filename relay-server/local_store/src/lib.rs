//! Local encrypted message store (SQLCipher via `rusqlite`).
//!
//! ## Why this table exists at all (see also /docs/KNOWN_LIMITATIONS.md, req. #4)
//!
//! The product brief's original schema called this column `plaintext_cache`,
//! which implies it's an optional, re-derivable convenience copy. It is not.
//!
//! The Double Ratchet deletes a message key the instant it's used to decrypt
//! one message — that's the whole point of forward secrecy: a later key or
//! device compromise cannot retroactively decrypt past traffic. That also
//! means *we* cannot re-decrypt a past message a second time; there is no
//! ratchet state left that could reproduce that specific message key. If we
//! only kept ciphertext locally, the message would become permanently
//! unreadable the moment the app restarts (there is no persisted ratchet
//! state in this MVP — see limitations) or, in a build that does persist
//! ratchet state, the moment the ratchet advances past it.
//!
//! So: local plaintext storage is not a caching optimization here, it is the
//! *only* durable copy of the message that will ever exist on this device.
//! We do not eliminate it — we protect it, by never storing it outside an
//! SQLCipher-encrypted database file, whose key is never itself written to
//! disk unencrypted (on Android this key must be generated inside and
//! unlocked only via the Keystore; see `open` below for exactly where that
//! boundary is in this simulation).
//!
//! We do NOT store the consumed ciphertext at all — once a message is
//! decrypted there is no remaining use for the ciphertext, and keeping it
//! around would be pure downside (more data to protect, no benefit).

use rusqlite::{Connection, OptionalExtension, Result as SqlResult};

pub struct LocalStore {
    conn: Connection,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Direction {
    Outgoing,
    Incoming,
}

impl Direction {
    fn as_db_str(&self) -> &'static str {
        match self {
            Direction::Outgoing => "outgoing",
            Direction::Incoming => "incoming",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredMessage {
    pub id: i64,
    pub conversation_id: String,
    pub sender_id: String,
    pub direction: String,
    pub plaintext_body: String,
    pub sent_at_unix_ms: i64,
}

impl LocalStore {
    /// Open (creating if needed) an SQLCipher-encrypted database at `path`.
    ///
    /// `key_hex` must be 64 hex characters (32 raw bytes). It is applied via
    /// `PRAGMA key = "x'...'"`, which tells SQLCipher to use the bytes
    /// directly as the encryption key rather than running its own PBKDF2
    /// pass over a passphrase — appropriate here because the key is already
    /// high-entropy random data, not a human-chosen password.
    ///
    /// ## Where this key is supposed to come from
    ///
    /// On a real Android build, `key_hex` must never be a value the app
    /// generates and then writes to a plaintext file next to the database.
    /// It must be generated inside, and only ever released by, the Android
    /// Keystore (hardware-backed where available), gated behind
    /// biometric/device-credential auth — exactly as described in the
    /// architecture doc. This Rust simulation has no Keystore to call, so
    /// the caller (see `device` crate) generates this key with the OS CSPRNG
    /// and holds it only in process memory for the lifetime of the demo. It
    /// is never written to disk by this crate. That in-memory-only handling
    /// is the correct *simulation* of "protected by hardware keystore, never
    /// persisted in the clear" — it is not itself hardware-backed, and this
    /// is called out explicitly in /docs/KNOWN_LIMITATIONS.md.
    pub fn open(path: &str, key_hex: &str) -> SqlResult<Self> {
        assert_eq!(
            key_hex.len(),
            64,
            "key_hex must be 64 hex chars (32 bytes) for a raw SQLCipher key"
        );
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "key", format!("x'{key_hex}'"))?;

        // Fail loudly and immediately if the key is wrong / the file isn't
        // actually an SQLCipher database, instead of silently proceeding
        // with an unusable connection.
        conn.pragma_update(None, "cipher_compatibility", 4)?;
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |row| {
            row.get::<_, i64>(0)
        })?;

        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                sender_id TEXT NOT NULL,
                direction TEXT NOT NULL CHECK (direction IN ('outgoing','incoming')),
                plaintext_body TEXT NOT NULL,
                sent_at_unix_ms INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_messages_conversation
                ON messages(conversation_id, sent_at_unix_ms);",
        )?;

        Ok(Self { conn })
    }

    pub fn insert_message(
        &self,
        conversation_id: &str,
        sender_id: &str,
        direction: Direction,
        plaintext_body: &str,
        sent_at_unix_ms: i64,
    ) -> SqlResult<i64> {
        self.conn.execute(
            "INSERT INTO messages (conversation_id, sender_id, direction, plaintext_body, sent_at_unix_ms)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            rusqlite::params![
                conversation_id,
                sender_id,
                direction.as_db_str(),
                plaintext_body,
                sent_at_unix_ms
            ],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn messages_for_conversation(&self, conversation_id: &str) -> SqlResult<Vec<StoredMessage>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, conversation_id, sender_id, direction, plaintext_body, sent_at_unix_ms
             FROM messages WHERE conversation_id = ?1 ORDER BY sent_at_unix_ms ASC, id ASC",
        )?;
        let rows = stmt.query_map([conversation_id], |row| {
            Ok(StoredMessage {
                id: row.get(0)?,
                conversation_id: row.get(1)?,
                sender_id: row.get(2)?,
                direction: row.get(3)?,
                plaintext_body: row.get(4)?,
                sent_at_unix_ms: row.get(5)?,
            })
        })?;
        rows.collect()
    }

    pub fn message_count(&self, conversation_id: &str) -> SqlResult<i64> {
        self.conn
            .query_row(
                "SELECT count(*) FROM messages WHERE conversation_id = ?1",
                [conversation_id],
                |row| row.get(0),
            )
            .optional()
            .map(|v| v.unwrap_or(0))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_db_path(name: &str) -> String {
        let dir = std::env::temp_dir();
        let pid = std::process::id();
        format!(
            "{}/vslice_test_{}_{}_{}.db",
            dir.display(),
            name,
            pid,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        )
    }

    fn random_key_hex() -> String {
        use std::io::Read;
        let mut f = std::fs::File::open("/dev/urandom").expect("os rng");
        let mut buf = [0u8; 32];
        f.read_exact(&mut buf).expect("read random bytes");
        buf.iter().map(|b| format!("{b:02x}")).collect()
    }

    #[test]
    fn write_and_read_back_a_message() {
        let path = temp_db_path("rw");
        let key = random_key_hex();
        let store = LocalStore::open(&path, &key).expect("open store");
        store
            .insert_message("conv:alice:bob", "alice", Direction::Outgoing, "hi bob", 1000)
            .expect("insert");
        let msgs = store.messages_for_conversation("conv:alice:bob").expect("read");
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].plaintext_body, "hi bob");
        assert_eq!(msgs[0].sender_id, "alice");
        assert_eq!(msgs[0].direction, "outgoing");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn database_file_is_unreadable_without_the_correct_key() {
        let path = temp_db_path("locked");
        let key = random_key_hex();
        {
            let store = LocalStore::open(&path, &key).expect("open store");
            store
                .insert_message("conv:x", "alice", Direction::Outgoing, "secret content", 1)
                .expect("insert");
        }

        // Re-open the same file with a different (wrong) key.
        let wrong_key = random_key_hex();
        let reopened = Connection::open(&path).expect("open file handle");
        reopened
            .pragma_update(None, "key", format!("x'{wrong_key}'"))
            .expect("set wrong key pragma");
        let result: rusqlite::Result<i64> =
            reopened.query_row("SELECT count(*) FROM sqlite_master", [], |r| r.get(0));

        assert!(
            result.is_err(),
            "expected the database to be unreadable with the wrong key, but it opened"
        );

        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn raw_file_bytes_do_not_contain_the_plaintext() {
        let path = temp_db_path("rawbytes");
        let key = random_key_hex();
        let needle = "PLAINTEXT_CANARY_STRING_should_not_appear_on_disk";
        {
            let store = LocalStore::open(&path, &key).expect("open store");
            store
                .insert_message("conv:y", "bob", Direction::Incoming, needle, 1)
                .expect("insert");
        }
        let raw = std::fs::read(&path).expect("read raw db file");
        let raw_str = String::from_utf8_lossy(&raw);
        assert!(
            !raw_str.contains(needle),
            "found plaintext message content in the raw, at-rest database file"
        );
        let _ = std::fs::remove_file(&path);
    }
}
