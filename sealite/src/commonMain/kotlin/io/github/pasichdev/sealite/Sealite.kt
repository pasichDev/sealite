// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import androidx.sqlite.throwSQLiteException
import io.github.pasichdev.sealite.internal.ExcludeFromCoverage
import io.github.pasichdev.sealite.internal.OpenSequence
import io.github.pasichdev.sealite.internal.RawDb
import io.github.pasichdev.sealite.internal.createUniqueFileNextTo
import io.github.pasichdev.sealite.internal.databaseFiles
import io.github.pasichdev.sealite.internal.deleteFileIfExists
import io.github.pasichdev.sealite.internal.exec
import io.github.pasichdev.sealite.internal.publishWithoutReplacing
import io.github.pasichdev.sealite.internal.queryString
import io.github.pasichdev.sealite.internal.readFileHeader
import io.github.pasichdev.sealite.internal.wipe
import kotlin.jvm.JvmStatic
import kotlin.random.Random

/**
 * Maintenance operations: a pre-flight [check] before Room opens a file, [rekey] to change the
 * key, [encryptPlaintext] to migrate an unencrypted database, [errorOf] to tell why an open
 * failed, and [versions] to see what is linked in.
 *
 * [rekey] and [encryptPlaintext] need the file to themselves: close the Room database first.
 * SQLCipher's full memory sanitization (`cipher_memory_security`) is always on.
 */
public object Sealite {
    /**
     * The outcome of [check]. New results may be added in minor releases: keep an `else` branch
     * when matching.
     */
    public sealed interface CheckResult {
        /** The key opens the database, or the file is empty (a new database). */
        public data object Ok : CheckResult

        /** There is no file at the path. */
        public data object Missing : CheckResult

        /**
         * The file starts with SQLite's plaintext header: an unencrypted database (see
         * [encryptPlaintext]), or a SQLCipher file written with `cipher_plaintext_header_size`,
         * which Sealite does not open.
         */
        public data object NotEncrypted : CheckResult

        /**
         * The key does not open the file. An encrypted database is indistinguishable from random
         * bytes by design, so a damaged file or one that is not a database at all also lands here.
         */
        public data object WrongKey : CheckResult
    }

    /**
     * The [SealiteException.error] of [throwable] or of the first [SealiteException] among its
     * causes (Room and coroutines may wrap it); `null` when the failure is not one of Sealite's
     * (an I/O error, a constraint, ...). Decided by type, never by message text.
     */
    @JvmStatic
    public fun errorOf(throwable: Throwable): SealiteError? {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is SealiteException) return current.error
            current = current.cause
            depth++
        }
        return null
    }

    /** [check] with the default [SealiteConfig]. */
    @JvmStatic
    public fun check(path: String, key: SealiteKey): CheckResult = check(path, key, SealiteConfig.DEFAULT)

    /**
     * Tells whether [key] opens the database at [path] with [config]. The file is opened
     * read-only and never written (SQLite may still create a `-shm` file next to a WAL database).
     */
    @JvmStatic
    public fun check(path: String, key: SealiteKey, config: SealiteConfig): CheckResult {
        val header = readFileHeader(path, PLAINTEXT_HEADER.size) ?: return CheckResult.Missing
        if (header.isEmpty()) return CheckResult.Ok
        if (header.contentEquals(PLAINTEXT_HEADER)) return CheckResult.NotEncrypted
        return try {
            OpenSequence.openVerified(path, OPEN_READONLY, key, config).close()
            CheckResult.Ok
        } catch (e: SealiteException) {
            wrongKeyOrRethrow(e)
        }
    }

    // Any other error is defensive: a plaintext file is answered above, and a linked SQLite without
    // encryption or a connection that is not keyed would need a broken build, not a test file.
    @ExcludeFromCoverage
    private fun wrongKeyOrRethrow(e: SealiteException): CheckResult =
        if (e.error == SealiteError.WRONG_KEY) CheckResult.WrongKey else throw e

    /** [rekey] with the default [SealiteConfig]. */
    @JvmStatic
    public fun rekey(path: String, oldKey: SealiteKey, newKey: SealiteKey): Unit =
        rekey(path, oldKey, newKey, SealiteConfig.DEFAULT)

    /**
     * Re-encrypts the database at [path] from [oldKey] to [newKey] in place.
     *
     * Close every other connection to the file first (the `RoomDatabase`). The file is locked
     * exclusively for the whole operation: a connection inside a transaction (or reading a WAL
     * database) makes this fail with `SQLITE_BUSY` before anything is written. An idle connection
     * that still holds [oldKey] does not block it; its next read then fails, it never returns wrong
     * data. After the commit the file is reopened with [newKey] and checked with
     * `PRAGMA quick_check`.
     *
     * SQLite's journal makes the change atomic: at any moment, including after a crash, the file is
     * entirely under [oldKey] or entirely under [newKey]. So store keys like this:
     * 1. Persist [newKey] as *pending*, keeping [oldKey].
     * 2. Call [rekey].
     * 3. Only when it returns, make [newKey] current and discard [oldKey].
     * 4. On start-up with a pending key: [check] with [newKey]; if that is not [CheckResult.Ok],
     *    [check] with [oldKey].
     *
     * The old pages are overwritten in place, but flash storage may keep old-key ciphertext in
     * blocks the file system no longer references.
     *
     * @throws SealiteException with [SealiteError.WRONG_KEY] if [oldKey] does not open the file;
     *   [SealiteError.REKEY_FAILED] if it failed before the commit, so the file is still under
     *   [oldKey]; [SealiteError.REKEY_UNVERIFIED] if it failed after the commit, so the file is most
     *   likely under [newKey] and both keys must be kept.
     * @throws androidx.sqlite.SQLiteException (for example `SQLITE_BUSY`) if SQLite stopped it
     *   before anything was written: the file is still under [oldKey].
     * @throws IllegalArgumentException if there is no database at [path].
     */
    // Each throw is a distinct, documented outcome (missing file, pre-commit, post-commit).
    @Suppress("ThrowsCount")
    @JvmStatic
    public fun rekey(path: String, oldKey: SealiteKey, newKey: SealiteKey, config: SealiteConfig) {
        require(readFileHeader(path, 1)?.isNotEmpty() == true) { "No database at $path" }
        val db = OpenSequence.openVerified(path, OPEN_READWRITE, oldKey, config)
        try {
            // Hold an exclusive lock until close: no other connection can read pages while they
            // change keys, and an open one makes this fail here, before anything is written.
            db.exec("PRAGMA locking_mode = EXCLUSIVE")
            db.exec("BEGIN EXCLUSIVE")
            db.exec("COMMIT")
            // WAL frames are decrypted with the connection's current key: fold them into the
            // main file before the key changes.
            val wal = db.queryString("PRAGMA journal_mode").equals("wal", ignoreCase = true)
            if (wal) checkpoint(db)

            val bytes = newKey.keyBytes()
            try {
                db.rekey(bytes)
            } finally {
                bytes.wipe()
            }
            requireCommitted(db)
            // From here on the new key is committed: any failure is "unverified", not "failed".
            checkpointCommitted(db, wal)
        } finally {
            db.close()
        }
        // Prove the file opens with the new key before the caller forgets the old one.
        val result = quickCheckWithNewKey(path, newKey, config)
        if (result != "ok") throw unverified("quick_check after rekey reported $result", null)
    }

    /**
     * sqlite3_rekey_v2 ignores the result of its commit: a still open transaction means the pages
     * were not committed, and closing would roll them back to the old key.
     */
    // Unreachable in a test: it needs the commit to fail (an I/O error) after the rekey succeeded.
    @ExcludeFromCoverage
    private fun requireCommitted(db: RawDb) {
        if (db.inTransaction()) {
            runCatching { db.exec("ROLLBACK") }
            throw SealiteException(SealiteError.REKEY_FAILED, "the re-encrypted pages could not be committed")
        }
    }

    // Unreachable in a test: the exclusive lock is held, so the checkpoint can only fail on an I/O error.
    @ExcludeFromCoverage
    private fun checkpointCommitted(db: RawDb, wal: Boolean) {
        try {
            if (wal) checkpoint(db)
        } catch (e: SQLiteException) {
            throw unverified("the WAL could not be checkpointed", e)
        }
    }

    // The failure branch is unreachable in a test: it needs an I/O error between the commit and the reopen.
    @ExcludeFromCoverage
    private fun quickCheckWithNewKey(path: String, newKey: SealiteKey, config: SealiteConfig): String? = try {
        val reopened = OpenSequence.openVerified(path, OPEN_READONLY, newKey, config)
        try {
            reopened.queryString("PRAGMA quick_check")
        } finally {
            reopened.close()
        }
    } catch (e: SQLiteException) {
        throw unverified("reopening with the new key failed", e)
    } catch (e: SealiteException) {
        throw unverified("reopening with the new key failed", e)
    }

    // Only the excluded post-commit failure paths pass a cause.
    @ExcludeFromCoverage
    private fun unverified(reason: String, cause: Throwable?): Throwable =
        SealiteException(SealiteError.REKEY_UNVERIFIED, reason, cause)

    /** [encryptPlaintext] with the default [SealiteConfig]. */
    @JvmStatic
    public fun encryptPlaintext(plainPath: String, encryptedPath: String, key: SealiteKey): Unit =
        encryptPlaintext(plainPath, encryptedPath, key, SealiteConfig.DEFAULT)

    /**
     * Writes an encrypted copy of the plaintext database at [plainPath] to [encryptedPath] with
     * `sqlcipher_export`: schema, rows, `user_version`, `application_id` and `auto_vacuum`. The
     * page size comes from [config], and the journal mode is not copied (Room sets its own).
     *
     * The copy is built in a new, uniquely named temporary file next to [encryptedPath] (created
     * exclusively, so concurrent migrations never share one), in one transaction, checked with
     * `PRAGMA integrity_check`, and only then published under [encryptedPath] without replacing
     * anything: [encryptedPath] either does not exist or is complete, and if another process
     * created it meanwhile this call fails instead of overwriting it. A failure removes the
     * temporary file; a crash may leave one behind (`.<name>.sealite-*`), safe to delete.
     *
     * The plaintext file is left as it is. Delete it (and its `-journal`, `-wal` and `-shm`) once
     * the copy is in use; deleting does not erase flash storage, so the plaintext may stay
     * recoverable from the device.
     *
     * @throws IllegalArgumentException if [plainPath] is missing or not a plaintext SQLite
     *   database, or [encryptedPath] or one of its sidecar files already exists.
     * @throws IllegalStateException if [encryptedPath] appeared while the copy was being built.
     *
     * On a file system without hard links (FAT, some network shares) it fails with the platform's
     * I/O exception: no other publish can be guaranteed never to replace a file, so none is
     * attempted. App-private storage on Android, APFS, NTFS and ext4 all support hard links.
     */
    // Any failure, whatever its type, must roll back and remove the temporary file, then rethrow.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    @JvmStatic
    public fun encryptPlaintext(plainPath: String, encryptedPath: String, key: SealiteKey, config: SealiteConfig) {
        val header =
            readFileHeader(plainPath, PLAINTEXT_HEADER.size)
                ?: throw IllegalArgumentException("No database at $plainPath")
        require(header.contentEquals(PLAINTEXT_HEADER)) { "$plainPath is not a plaintext SQLite database" }
        for (file in databaseFiles(encryptedPath)) {
            require(readFileHeader(file, 1) == null) { "$file already exists" }
        }

        val temp = createUniqueFileNextTo(encryptedPath)
        try {
            // The encrypted database is "main" and keyed through the API; the plaintext one is
            // attached with an empty key. No secret is ever part of SQL text.
            val db = OpenSequence.openVerified(temp, OPEN_READWRITE or OPEN_CREATE, key, config)
            try {
                db.prepare("ATTACH DATABASE ? AS plaintext KEY ''").use {
                    it.bindText(1, plainPath)
                    it.step()
                }
                // auto_vacuum only takes effect before the first table exists.
                val autoVacuum = db.pragmaInt("PRAGMA plaintext.auto_vacuum")
                db.exec("PRAGMA main.auto_vacuum = $autoVacuum")
                val userVersion = db.pragmaInt("PRAGMA plaintext.user_version")
                val applicationId = db.pragmaInt("PRAGMA plaintext.application_id")

                db.exec("BEGIN IMMEDIATE")
                try {
                    db.exec("SELECT sqlcipher_export('main', 'plaintext')")
                    db.exec("PRAGMA main.user_version = $userVersion")
                    db.exec("PRAGMA main.application_id = $applicationId")
                    db.exec("COMMIT")
                } catch (t: Throwable) {
                    runCatching { db.exec("ROLLBACK") }
                    throw t
                }
                db.exec("DETACH DATABASE plaintext")

                requireIntegrity(db)
            } finally {
                db.close()
            }
            publishWithoutReplacing(temp, encryptedPath)
        } catch (t: Throwable) {
            databaseFiles(temp).forEach { runCatching { deleteFileIfExists(it) } }
            throw t
        }
    }

    // SQLite always answers these pragmas with a number, so the fallback is unreachable.
    @ExcludeFromCoverage
    private fun RawDb.pragmaInt(sql: String): Int = queryString(sql)?.toIntOrNull() ?: 0

    // Unreachable in a test: sqlcipher_export copies through SQL, so the copy is consistent by construction.
    @ExcludeFromCoverage
    private fun requireIntegrity(db: RawDb) {
        val integrity = db.queryString("PRAGMA main.integrity_check")
        check(integrity == "ok") { "integrity_check of the encrypted copy reported $integrity" }
    }

    /**
     * What is compiled in: this library, SQLite, SQLCipher and its crypto provider. Read from the
     * linked native library on first access.
     */
    @JvmStatic
    public val versions: Versions by lazy { readVersions() }

    /**
     * The versions [Sealite.versions] reports. Fields may be added in minor releases.
     */
    public class Versions internal constructor(
        /** This library. */
        public val sealite: String,
        /** SQLite inside SQLCipher. */
        public val sqlite: String,
        /** SQLCipher, as `PRAGMA cipher_version` reports it. */
        public val sqlcipher: String,
        /** The crypto library SQLCipher uses (`openssl` or `commoncrypto`), from `PRAGMA cipher_provider`. */
        public val cryptoProvider: String,
        /** The version of [cryptoProvider]. */
        public val cryptoProviderVersion: String,
    ) {
        override fun equals(other: Any?): Boolean = other is Versions &&
            sealite == other.sealite &&
            sqlite == other.sqlite &&
            sqlcipher == other.sqlcipher &&
            cryptoProvider == other.cryptoProvider &&
            cryptoProviderVersion == other.cryptoProviderVersion

        override fun hashCode(): Int =
            listOf(sealite, sqlite, sqlcipher, cryptoProvider, cryptoProviderVersion).hashCode()

        override fun toString(): String = "Versions(sealite=$sealite, sqlite=$sqlite, sqlcipher=$sqlcipher, " +
            "cryptoProvider=$cryptoProvider, cryptoProviderVersion=$cryptoProviderVersion)"
    }

    /** An in-memory database with a throwaway key: it protects nothing, so a non-cryptographic random is fine. */
    private fun readVersions(): Versions {
        val throwaway = SealiteKey.raw(Random.nextBytes(RAW_KEY_SIZE))
        val db: RawDb
        try {
            db = OpenSequence.openVerified(":memory:", OPEN_READWRITE or OPEN_CREATE, throwaway, SealiteConfig.DEFAULT)
        } finally {
            throwaway.close()
        }
        try {
            return Versions(
                sealite = BuildInfo.VERSION,
                sqlite = db.text("SELECT sqlite_version()"),
                sqlcipher = db.text("PRAGMA cipher_version"),
                cryptoProvider = db.text("PRAGMA cipher_provider"),
                cryptoProviderVersion = db.text("PRAGMA cipher_provider_version"),
            )
        } finally {
            db.close()
        }
    }

    // SQLite answers each of these queries with a row, so the empty fallback is unreachable.
    @ExcludeFromCoverage
    private fun RawDb.text(sql: String): String = queryString(sql).orEmpty()

    /** `wal_checkpoint(TRUNCATE)` must fully succeed: its first column is 1 when it was blocked. */
    // The blocked branch is unreachable in a test: the exclusive lock rekey holds keeps every other
    // connection of a WAL database out, so nothing can block the checkpoint.
    @ExcludeFromCoverage
    private fun checkpoint(db: RawDb) {
        val busy = db.queryString("PRAGMA wal_checkpoint(TRUNCATE)")
        if (busy != "0") {
            throwSQLiteException(SQLITE_BUSY, "the WAL could not be checkpointed; close other connections")
        }
    }

    private val PLAINTEXT_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    private const val RAW_KEY_SIZE = 32
    private const val MAX_CAUSE_DEPTH = 16
    private const val SQLITE_BUSY = 5
    private const val OPEN_READONLY = 0x00000001
    private const val OPEN_READWRITE = 0x00000002
    private const val OPEN_CREATE = 0x00000004
}
