// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The less common outcomes of [Sealite]'s maintenance calls. */
class MaintenanceEdgeCasesTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    @Test
    fun errorOfGoesByTypeNotByMessage() {
        fun chain(depth: Int): Throwable {
            var current: Throwable = SealiteException(SealiteError.WRONG_KEY, "test")
            repeat(depth) { current = IllegalStateException("wrapper", current) }
            return current
        }
        assertEquals(SealiteError.WRONG_KEY, Sealite.errorOf(chain(3)))
        // A cause chain can be cyclic or absurdly long: the walk stops instead of following it.
        assertNull(Sealite.errorOf(chain(40)))
        assertNull(Sealite.errorOf(IllegalStateException()))
        // Message text never decides: a SQLite error that merely mentions the name is not Sealite's.
        assertNull(Sealite.errorOf(IllegalStateException("no such table: WRONG_KEY")))
        assertNull(Sealite.errorOf(IllegalStateException("SEALITE_WRONG_KEY: the key does not open")))
    }

    @Test
    fun versionsCompareFieldByField() {
        val v = Sealite.versions

        fun copy(
            sealite: String = v.sealite,
            sqlite: String = v.sqlite,
            sqlcipher: String = v.sqlcipher,
            provider: String = v.cryptoProvider,
            providerVersion: String = v.cryptoProviderVersion,
        ) = Sealite.Versions(sealite, sqlite, sqlcipher, provider, providerVersion)

        assertEquals(v, copy())
        assertEquals(v.hashCode(), copy().hashCode())
        assertEquals(v.toString(), copy().toString())
        assertTrue(v.toString().startsWith("Versions(sealite=${v.sealite}, "))
        assertNotEquals<Any?>(v, "not versions")
        assertNotEquals(v, copy(sealite = "other"))
        assertNotEquals(v, copy(sqlite = "other"))
        assertNotEquals(v, copy(sqlcipher = "other"))
        assertNotEquals(v, copy(provider = "other"))
        assertNotEquals(v, copy(providerVersion = "other"))
    }

    @Test
    fun checkRethrowsAFailureThatIsNotAWrongKey() {
        val path = newPath()
        withDb(path, rawKey(1)) { it.createNotes("x") }
        val holder: SQLiteConnection = SealiteDriver(rawKey(1)).open(path)
        try {
            // An exclusive lock in rollback-journal mode keeps even a reader out: SQLITE_BUSY.
            holder.execSQL("BEGIN EXCLUSIVE")
            val e = assertFailsWith<SQLiteException> { Sealite.check(path, rawKey(1)) }
            assertNull(Sealite.errorOf(e), "busy is not a key problem: ${e.message}")
        } finally {
            holder.close()
        }
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, rawKey(1)))
    }

    @Test
    fun rekeyRefusesAMissingOrEmptyFile() {
        val path = newPath()
        assertFailsWith<IllegalArgumentException> { Sealite.rekey(path, rawKey(1), rawKey(2)) }
        writeFileBytes(path, ByteArray(0))
        assertFailsWith<IllegalArgumentException> { Sealite.rekey(path, rawKey(1), rawKey(2)) }
        assertEquals(0, readFileBytes(path)?.size)
    }

    @Test
    fun rekeyReportsAFileThatOpensButIsNotHealthyAsUnverified() {
        val path = newPath()
        withDb(path, rawKey(1)) { db ->
            db.createNotes("a", "b")
            db.execSQL("CREATE INDEX note_body ON note (body)")
            // Every page still decrypts and the schema still loads, but the index now shares the
            // table's b-tree: quick_check can only tell after the rekey.
            db.execSQL("PRAGMA writable_schema = ON")
            db.execSQL(
                "UPDATE sqlite_schema SET rootpage = (SELECT rootpage FROM sqlite_schema WHERE name = 'note') " +
                    "WHERE name = 'note_body'",
            )
        }
        val e = assertFailsWith<SealiteException> { Sealite.rekey(path, rawKey(1), rawKey(2)) }
        assertEquals(SealiteError.REKEY_UNVERIFIED, e.error, e.toString())
        assertTrue(e.message.orEmpty().contains("quick_check after rekey reported"), e.message)
        assertTrue(e.suppressedExceptions.isEmpty())
        // The commit happened: the file is under the new key, as the error says.
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, rawKey(2)))
        assertNotNull(readFileBytes(path))
    }
}
