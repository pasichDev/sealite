// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.execSQL
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CheckTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    @Test
    fun plaintextIsNotEncryptedAndStaysUntouched() {
        val path = newPath()
        writeFileBytes(path, PLAINTEXT_DATABASE)
        assertEquals(Sealite.CheckResult.NotEncrypted, Sealite.check(path, rawKey(1)))
        assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(path))
        assertEquals(null, readFileBytes("$path-wal"))
    }

    @Test
    fun anEmptyFileIsOk() {
        val path = newPath()
        writeFileBytes(path, ByteArray(0))
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, rawKey(1)))
        assertEquals(0, readFileBytes(path)?.size)
    }

    @Test
    fun aMissingFileIsMissingAndIsNotCreated() {
        val path = newPath()
        assertEquals(Sealite.CheckResult.Missing, Sealite.check(path, rawKey(1)))
        assertEquals(null, readFileBytes(path))
    }

    @Test
    fun randomBytesAreWrongKey() {
        val path = newPath()
        val random = Random(42).nextBytes(8192)
        writeFileBytes(path, random)
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(path, rawKey(1)))
        assertContentEquals(random, readFileBytes(path))
    }

    @Test
    fun theRightKeyIsOkAndAnotherIsWrongKey() {
        val path = newPath()
        withDb(path, rawKey(1)) { it.createNotes("a") }
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, rawKey(1)))
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(path, rawKey(2)))
    }

    /**
     * The files a process killed in the middle of a large write leaves behind: the database with some
     * pages already changed, and the journal that undoes them. Returns the path and the committed rows.
     */
    private fun crashedInTheMiddleOfAWrite(): Pair<String, List<String>> {
        val path = newPath()
        val image = newPath()
        return withDb(path, rawKey(1)) { db ->
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
            db.execSQL(
                "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 4000) " +
                    "INSERT INTO t (body) SELECT hex(randomblob(200)) FROM c",
            )
            val committed = db.column("SELECT body FROM t ORDER BY id")
            // A cache of five pages makes the transaction write its pages to the file before it commits.
            db.execSQL("PRAGMA cache_size = 5")
            db.execSQL("BEGIN")
            db.execSQL("UPDATE t SET body = hex(randomblob(200))")
            writeFileBytes(image, assertNotNull(readFileBytes(path)))
            writeFileBytes("$image-journal", assertNotNull(readFileBytes("$path-journal"), "no journal"))
            db.execSQL("ROLLBACK")
            image to committed
        }
    }

    @Test
    fun aDatabaseACrashLeftWithAHotJournalIsAnsweredForBothKeysAndRolledBack() {
        val (image, committed) = crashedInTheMiddleOfAWrite()
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(image, rawKey(2)))
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(image, rawKey(1)))
        assertEquals(null, readFileBytes("$image-journal"), "the journal was rolled back")
        withDb(image, rawKey(1)) { db ->
            assertEquals("ok", db.scalar("PRAGMA integrity_check"))
            assertEquals(committed, db.column("SELECT body FROM t ORDER BY id"))
        }
    }
}
