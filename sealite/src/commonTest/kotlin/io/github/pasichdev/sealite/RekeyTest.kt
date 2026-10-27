// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RekeyTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    private val rows = (1..50).map { "row-$it" }

    private fun create(path: String, key: SealiteKey, wal: Boolean = false) {
        withDb(path, key) { db ->
            if (wal) db.execSQL("PRAGMA journal_mode = WAL")
            db.createNotes(*rows.toTypedArray())
        }
    }

    private fun assertRekeyed(path: String, old: () -> SealiteKey, new: () -> SealiteKey) {
        assertEquals(SealiteError.WRONG_KEY, openError(path, old()))
        assertEquals(rows, withDb(path, new()) { it.notes() })
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, new()))
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(path, old()))
        assertFalse(assertNotNull(readFileBytes(path)).startsWith(SQLITE_HEADER))
    }

    @Test
    fun rollbackJournalDatabase() {
        val path = newPath()
        create(path, rawKey(1))
        Sealite.rekey(path, rawKey(1), rawKey(2))
        assertRekeyed(path, { rawKey(1) }, { rawKey(2) })
        assertNull(readFileBytes("$path-journal"), "a hot journal was left behind")
    }

    @Test
    fun walDatabaseWithRowsStillInTheWal() {
        val source = newPath()
        val path = newPath()
        val keeper = SealiteDriver(rawKey(1))
        try {
            // Keep a connection open so the rows live in the -wal, then copy the pair: the copy is
            // what a crash would leave behind (a database whose newest rows are only in the WAL).
            keeper.open(source).use { db ->
                db.execSQL("PRAGMA journal_mode = WAL")
                db.execSQL("PRAGMA wal_autocheckpoint = 0")
                db.createNotes(*rows.toTypedArray())
                val wal = assertNotNull(readFileBytes("$source-wal"))
                assertTrue(wal.size > 32, "expected frames in the -wal")
                writeFileBytes(path, assertNotNull(readFileBytes(source)))
                writeFileBytes("$path-wal", wal)
            }
        } finally {
            keeper.close()
        }
        assertTrue(assertNotNull(readFileBytes("$path-wal")).size > 32)

        Sealite.rekey(path, rawKey(1), rawKey(2))
        assertRekeyed(path, { rawKey(1) }, { rawKey(2) })
        // Still a WAL database afterwards, and no old-key frames survive.
        assertEquals("wal", withDb(path, rawKey(2)) { it.scalar("PRAGMA journal_mode") })
    }

    @Test
    fun rawToPassphraseAndBack() {
        val path = newPath()
        val pass = { SealiteKey.passphrase("кава ☕ and tea".toCharArray()) }
        create(path, rawKey(1))
        Sealite.rekey(path, rawKey(1), pass())
        assertRekeyed(path, { rawKey(1) }, pass)

        Sealite.rekey(path, pass(), rawKey(3))
        assertRekeyed(path, pass, { rawKey(3) })
    }

    @Test
    fun rekeyHonoursTheConfig() {
        val path = newPath()
        val config = SealiteConfig { compatibility = SealiteConfig.Compatibility.SQLCIPHER_3 }
        val pass = { SealiteKey.passphrase("legacy".toCharArray()) }
        val other = { SealiteKey.passphrase("legacy-2".toCharArray()) }
        withDb(path, pass(), config) { it.createNotes(*rows.toTypedArray()) }
        Sealite.rekey(path, pass(), other(), config)
        assertEquals(rows, withDb(path, other(), config) { it.notes() })
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(path, pass(), config))
    }

    @Test
    fun aWrongOldKeyFailsAndChangesNothing() {
        val path = newPath()
        create(path, rawKey(1))
        val before = assertNotNull(readFileBytes(path))
        val e = assertFailsWith<SealiteException> { Sealite.rekey(path, rawKey(9), rawKey(2)) }
        assertEquals(SealiteError.WRONG_KEY, e.error)
        kotlin.test.assertContentEquals(before, readFileBytes(path))
        assertEquals(rows, withDb(path, rawKey(1)) { it.notes() })
    }

    @Test
    fun aMissingFileIsRejected() {
        val path = newPath()
        assertFailsWith<IllegalArgumentException> { Sealite.rekey(path, rawKey(1), rawKey(2)) }
        assertNull(readFileBytes(path), "rekey must not create a database")
    }

    private fun assertBusyAndUnchanged(block: () -> Unit) {
        val e = assertFails { block() }
        val error = Sealite.errorOf(e)
        val busy = e.message.orEmpty().contains("Error code: 5") || e.message.orEmpty().contains("Error code: 517")
        assertTrue(
            error == SealiteError.REKEY_FAILED || busy,
            "expected SQLITE_BUSY or REKEY_FAILED, got $error: ${e.message}",
        )
        assertTrue(error != SealiteError.REKEY_UNVERIFIED, "nothing was committed: ${e.message}")
    }

    @Test
    fun rekeyWithAnotherConnectionReadingFailsAndTheOldKeyStillOpens() {
        val path = newPath()
        create(path, rawKey(1))
        val reader = SealiteDriver(rawKey(1)).open(path)
        try {
            // An open read transaction holds a SHARED lock: rekey can not get the exclusive one.
            reader.execSQL("BEGIN")
            assertEquals(rows.size.toString(), reader.scalar("SELECT count(*) FROM note"))
            assertBusyAndUnchanged { Sealite.rekey(path, rawKey(1), rawKey(2)) }
        } finally {
            reader.close()
        }
        assertEquals(rows, withDb(path, rawKey(1)) { it.notes() })
        assertEquals(SealiteError.WRONG_KEY, openError(path, rawKey(2)))
        assertNull(readFileBytes("$path-journal"), "a hot journal was left behind")
    }

    @Test
    fun rekeyWithAnotherConnectionWritingInWalFailsAndTheOldKeyStillOpens() {
        val path = newPath()
        create(path, rawKey(1), wal = true)
        val writer = SealiteDriver(rawKey(1)).open(path)
        try {
            writer.execSQL("BEGIN IMMEDIATE")
            writer.execSQL("INSERT INTO note (body) VALUES ('pending')")
            assertBusyAndUnchanged { Sealite.rekey(path, rawKey(1), rawKey(2)) }
            writer.execSQL("ROLLBACK")
        } finally {
            writer.close()
        }
        assertEquals(rows, withDb(path, rawKey(1)) { it.notes() })
        assertEquals(SealiteError.WRONG_KEY, openError(path, rawKey(2)))
    }

    @Test
    fun anIdleOpenConnectionDoesNotStopRekeyAndNeverReadsGarbage() {
        // In the rollback-journal mode an idle connection holds no lock, so SQLite lets rekey
        // through (only a connection with an open transaction makes it fail with SQLITE_BUSY).
        // The stale connection must then fail or read the real rows, never return garbage.
        val path = newPath()
        create(path, rawKey(1))
        val idle: SQLiteConnection = SealiteDriver(rawKey(1)).open(path)
        try {
            Sealite.rekey(path, rawKey(1), rawKey(2))
            val count = runCatching { idle.scalar("SELECT count(*) FROM note") }
            assertTrue(count.isFailure || count.getOrNull() == rows.size.toString(), "read after rekey: $count")
        } finally {
            idle.close()
        }
        assertEquals(rows, withDb(path, rawKey(2)) { it.notes() })
        assertEquals(SealiteError.WRONG_KEY, openError(path, rawKey(1)))
    }
}
