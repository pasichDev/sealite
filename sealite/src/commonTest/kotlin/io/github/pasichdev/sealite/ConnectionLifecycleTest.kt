// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import io.github.pasichdev.sealite.internal.RawDb
import io.github.pasichdev.sealite.internal.rawOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One lock per connection: closing the connection (or a statement) while another thread is inside a
 * native call must neither crash nor touch freed memory.
 */
class ConnectionLifecycleTest {
    private val flags = 0x02 or 0x04 // READWRITE | CREATE

    // A long query: counting a recursive CTE of this size takes seconds unless it is interrupted.
    private val longQuery =
        "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 300000000) SELECT count(*) FROM c"

    // Many short steps: the statement never holds the lock for long.
    private val manyRows =
        "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 100000) SELECT x FROM c"

    private fun calls(): List<Pair<String, (SQLiteStatement) -> Unit>> = listOf(
        "bindBlob" to { it.bindBlob(1, byteArrayOf(1)) },
        "bindDouble" to { it.bindDouble(1, 1.0) },
        "bindLong" to { it.bindLong(1, 1L) },
        "bindText" to { it.bindText(1, "x") },
        "bindNull" to { it.bindNull(1) },
        "getBlob" to { it.getBlob(0) },
        "getDouble" to { it.getDouble(0) },
        "getLong" to { it.getLong(0) },
        "getText" to { it.getText(0) },
        "isNull" to { it.isNull(0) },
        "getColumnCount" to { it.getColumnCount() },
        "getColumnName" to { it.getColumnName(0) },
        "getColumnType" to { it.getColumnType(0) },
        "step" to { it.step() },
        "reset" to { it.reset() },
        "clearBindings" to { it.clearBindings() },
    )

    @Test
    fun everyStatementCallAfterTheConnectionClosedSaysSo() {
        val db = rawOpen(":memory:", flags)
        val statement = db.prepare("SELECT ?")
        db.close()
        for ((name, call) in calls()) {
            val e = assertFailsWith<SQLiteException>(name) { call(statement) }
            assertTrue(e.message.orEmpty().contains("connection is closed"), "$name: ${e.message}")
        }
        statement.close() // already finalized by the connection: a no-op, not a double free
        statement.close()
    }

    @Test
    fun closingTheConnectionFinalizesTheStatementsStillOpen() {
        val db = rawOpen(":memory:", flags)
        val open = List(5) { db.prepare("SELECT $it") }
        open[1].close() // one closed before, one stepped
        assertTrue(open[2].step())
        db.close()
        open.forEach { it.close() }
        assertFailsWith<SQLiteException> { open[3].step() }
    }

    @Test
    fun aStatementClosedOnItsOwnSaysSo() {
        val db = rawOpen(":memory:", flags)
        val statement = db.prepare("SELECT 1")
        statement.close()
        val e = assertFailsWith<SQLiteException> { statement.step() }
        assertTrue(e.message.orEmpty().contains("statement is closed"), e.message)
        db.close()
    }

    @Test
    fun closingTheConnectionInterruptsAQueryRunningOnAnotherThread() = runTest {
        repeat(40) { round ->
            val db = rawOpen(":memory:", flags)
            val statement = db.prepare(longQuery)
            val outcome =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val stepper = async { runCatching { statement.step() } }
                        val closer =
                            async {
                                // Every other round the query has surely been running for a while,
                                // so close() has to interrupt it rather than get the lock for free.
                                delay(if (round % 2 == 0) 30L else Random.nextLong(0, 15))
                                db.close()
                            }
                        closer.await()
                        stepper.await()
                    }
                }
            val failure = assertNotNull(outcome.exceptionOrNull(), "the query can not finish in time")
            assertTrue(failure is SQLiteException, failure.toString())
            val message = failure.message.orEmpty()
            assertTrue("interrupt" in message || "closed" in message, message)
        }
    }

    @Test
    fun closingTheConnectionWhileAnotherThreadStepsStatementAfterStatement() = runTest {
        repeat(60) {
            val db = rawOpen(":memory:", flags)
            val outcome =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val stepper =
                            async {
                                runCatching {
                                    while (true) {
                                        db.prepare(manyRows).use { s ->
                                            while (s.step()) s.getLong(0)
                                            s.reset()
                                            s.clearBindings()
                                        }
                                    }
                                }
                            }
                        val closer =
                            async {
                                delay(Random.nextLong(0, 20))
                                db.close()
                            }
                        closer.await()
                        stepper.await()
                    }
                }
            assertTrue(outcome.exceptionOrNull() is SQLiteException, outcome.toString())
        }
    }

    @Test
    fun closingAStatementWhileAnotherThreadStepsIt() = runTest {
        val db: RawDb = rawOpen(":memory:", flags)
        try {
            repeat(60) {
                val statement = db.prepare(manyRows)
                val outcome =
                    withContext(Dispatchers.Default) {
                        coroutineScope {
                            val stepper =
                                async {
                                    runCatching {
                                        while (true) {
                                            while (statement.step()) statement.getLong(0)
                                            statement.reset()
                                        }
                                    }
                                }
                            val closer =
                                async {
                                    delay(Random.nextLong(0, 10))
                                    statement.close()
                                }
                            closer.await()
                            stepper.await()
                        }
                    }
                val failure = outcome.exceptionOrNull()
                assertTrue(failure is SQLiteException, outcome.toString())
                assertTrue("statement is closed" in failure.message.orEmpty(), failure.message)
            }
            // The connection survived all of it.
            db.prepare("SELECT 1").use { assertTrue(it.step()) }
        } finally {
            db.close()
        }
    }

    @Test
    fun manyThreadsOnOneConnectionAreSerialized() = runTest {
        val db = rawOpen(":memory:", flags)
        try {
            val sums =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        List(8) {
                            async {
                                var sum = 0L
                                repeat(20) {
                                    db.prepare("SELECT 21 * 2").use { s ->
                                        assertTrue(s.step())
                                        sum += s.getLong(0)
                                    }
                                }
                                sum
                            }
                        }.awaitAll()
                    }
                }
            assertEquals(List(8) { 840L }, sums)
        } finally {
            db.close()
        }
    }
}
