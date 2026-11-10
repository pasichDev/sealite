// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Closing is idempotent and race free: a handle is released exactly once. */
class CloseTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    @Test
    fun repeatedCloseIsHarmless() {
        val path = newPath()
        SealiteDriver(rawKey(1)).use { driver ->
            val db = driver.open(path)
            val statement = db.prepare("SELECT 1")
            statement.close()
            statement.close()
            db.close()
            db.close()
        }
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, rawKey(1)))
    }

    @Test
    fun closingTheLastConnectionCheckpointsTheWalAndRemovesIt() {
        val path = newPath()
        withDb(path, rawKey(1)) { db ->
            db.execSQL("PRAGMA journal_mode = WAL")
            db.createNotes("a", "b")
        }
        // SQLite's own rule for the last connection of a WAL database: checkpoint, then delete the
        // -wal. A file left behind means the rows are not in the main database file yet.
        assertNull(readFileBytes("$path-wal"), "the -wal survived the close")
        withDb(path, rawKey(1)) { assertEquals(listOf("a", "b"), it.notes()) }
    }

    @Test
    fun aCloseThatInterruptsARunningQueryStillCheckpointsTheWal() = runTest {
        val path = newPath()
        SealiteDriver(rawKey(1)).use { driver ->
            val db = driver.open(path)
            db.execSQL("PRAGMA journal_mode = WAL")
            db.createNotes("a", "b")
            // Counting a recursive CTE of this size takes seconds unless it is interrupted.
            val statement =
                db.prepare(
                    "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 300000000) " +
                        "SELECT count(*) FROM c",
                )
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val stepper = async { runCatching { statement.step() } }
                    delay(50) // the query is running: close() has to interrupt it
                    db.close()
                    assertTrue(stepper.await().isFailure, "the query can not finish in time")
                }
            }
        }
        assertNull(readFileBytes("$path-wal"), "the -wal survived the close")
        withDb(path, rawKey(1)) { assertEquals(listOf("a", "b"), it.notes()) }
    }

    @Test
    fun racingClosesReleaseTheHandleOnce() = runTest {
        val path = newPath()
        SealiteDriver(rawKey(1)).use { driver ->
            repeat(50) {
                val db = driver.open(path)
                val statements = List(4) { db.prepare("SELECT 1") }
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val jobs =
                            statements.flatMap { s -> List(4) { async { s.close() } } } +
                                List(8) { async { db.close() } }
                        jobs.awaitAll()
                    }
                }
            }
        }
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, rawKey(1)))
    }
}
