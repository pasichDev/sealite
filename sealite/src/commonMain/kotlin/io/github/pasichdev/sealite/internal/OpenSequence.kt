// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.throwSQLiteException
import io.github.pasichdev.sealite.SealiteConfig
import io.github.pasichdev.sealite.SealiteError
import io.github.pasichdev.sealite.SealiteKey

/**
 * The only way a [RawDb] becomes a connection: key, configure, verify, and only then wrap.
 * Any failure closes the native handle before the exception leaves, so a half-opened (unkeyed or
 * unverified) handle is never reachable.
 */
internal object OpenSequence {
    fun open(fileName: String, flags: Int, key: SealiteKey, config: SealiteConfig): SQLiteConnection =
        SealiteConnection(openVerified(fileName, flags, key, config))

    /** A keyed, verified handle for [io.github.pasichdev.sealite.Sealite]'s maintenance calls. */
    fun openVerified(fileName: String, flags: Int, key: SealiteKey, config: SealiteConfig): RawDb {
        if (isPlaintextDatabase(fileName)) {
            throwSQLiteException(
                SQLITE_NOTADB,
                "${SealiteError.NOT_ENCRYPTED.token}: an unencrypted SQLite database (or a SQLCipher file " +
                    "with a plaintext header, which Sealite does not support)",
            )
        }
        val db = rawOpen(fileName, flags)
        try {
            applyKey(db, key)
            configure(db, config)
            verify(db)
        } catch (t: Throwable) {
            db.close()
            throw t
        }
        return db
    }

    /** True when [fileName] is an existing file that starts with SQLite's plaintext header. */
    fun isPlaintextDatabase(fileName: String): Boolean {
        if (fileName == ":memory:" || fileName.isEmpty() || fileName.startsWith("file:")) return false
        return readFileHeader(fileName, PLAINTEXT_HEADER.size)?.contentEquals(PLAINTEXT_HEADER) == true
    }

    /** Keys [db] without leaving the key in Kotlin memory longer than the native call. */
    fun applyKey(db: RawDb, key: SealiteKey) {
        val bytes = key.keyBytes()
        try {
            // Backstop against a wiped buffer: close() zero-fills the whole key material (the raw
            // literal included), so all-zero bytes mean a key was used after being wiped. All-zero
            // raw keys supplied by the caller are rejected earlier, in SealiteKey.raw.
            if (bytes.all { it == 0.toByte() }) throwSQLiteException(SQLITE_MISUSE, "the key is all zero bytes")
            db.key(bytes)
        } finally {
            bytes.wipe()
        }
    }

    /** Cipher settings, in the order SQLCipher requires: compatibility first, overrides after. */
    fun configure(db: RawDb, config: SealiteConfig) {
        // Room sets its own timeout later; until then wait for other connections instead of
        // failing verification with SQLITE_BUSY (a failed open can lead Room into recovery).
        db.exec("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
        db.exec("PRAGMA cipher_compatibility = ${config.compatibility.pragmaValue}")
        config.kdfIterations?.let { db.exec("PRAGMA kdf_iter = $it") }
        config.pageSize?.let { db.exec("PRAGMA cipher_page_size = $it") }
        // Library-wide in SQLCipher and one-way (it can be turned on, never off): always on.
        db.exec("PRAGMA cipher_memory_security = ON")
    }

    /**
     * 1. `cipher_version` must be non-empty: otherwise a plain SQLite answered and the key was
     *    silently ignored.
     * 2. Reading the schema must work: it decrypts page 1, so a wrong key fails here
     *    (`SQLITE_NOTADB`) instead of on the caller's first query. A new, empty file has no page
     *    yet; nothing is written to it here, so Room still sees an untouched file.
     * 3. `cipher_status` (SQLCipher 4.12+) must be 1: the handle is keyed and its codec is not in
     *    an error state. Checked after 2 so a wrong key is still reported as such.
     */
    fun verify(db: RawDb) {
        val cipherVersion = db.queryString("PRAGMA cipher_version")
        if (cipherVersion.isNullOrEmpty()) {
            throwSQLiteException(
                SQLITE_MISUSE,
                "${SealiteError.PROVIDER_MISSING.token}: the linked SQLite has no encryption",
            )
        }
        try {
            db.queryString("SELECT count(*) FROM sqlite_schema")
        } catch (e: SQLiteException) {
            if (e.isNotADatabase()) {
                throwSQLiteException(
                    SQLITE_NOTADB,
                    "${SealiteError.WRONG_KEY.token}: the key does not open this database",
                )
            }
            throw e
        }
        if (db.queryString("PRAGMA cipher_status") != "1") {
            throwSQLiteException(
                SQLITE_MISUSE,
                "${SealiteError.NOT_KEYED.token}: the connection is not using encryption",
            )
        }
    }

    private val PLAINTEXT_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    private const val BUSY_TIMEOUT_MS = 5_000

    const val SQLITE_MISUSE = 21
    const val SQLITE_NOTADB = 26

    // Our bindings (ported from androidx) report "Error code: <n>, message: …"; extended codes
    // keep the primary code in the low byte.
    private fun SQLiteException.isNotADatabase(): Boolean {
        val code =
            message?.let {
                Regex("""Error code: (\d+)""")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }
        return code != null && (code and 0xFF) == SQLITE_NOTADB
    }
}

internal fun RawDb.exec(sql: String) {
    prepare(sql).use { statement ->
        @Suppress("ControlFlowWithEmptyBody")
        while (statement.step()) {
        }
    }
}

/** First column of the first row as text, or null for no row / NULL. */
internal fun RawDb.queryString(sql: String): String? = prepare(sql).use { statement ->
    if (statement.step() && !statement.isNull(0)) statement.getText(0) else null
}
