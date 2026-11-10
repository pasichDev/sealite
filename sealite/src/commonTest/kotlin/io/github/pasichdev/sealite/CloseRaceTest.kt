// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import io.github.pasichdev.sealite.internal.rawOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Close racing prepare, bind, step, reset and statement close on several threads, with fixed seeds
 * for the delays and a bound on the run time. A call that loses the race ends in a [SQLiteException];
 * after the close returned, every call says "closed". Nothing may crash, hang or throw anything else.
 */
class CloseRaceTest {
    private val flags = 0x02 or 0x04 // READWRITE | CREATE

    @Test
    fun closeRacingPrepareBindStepAndResetOnManyThreads() = runTest(timeout = 120.seconds) {
        repeat(80) { round ->
            val random = Random(SEED + round)
            val db = rawOpen(":memory:", flags)
            val closeAfter = random.nextLong(0, 12)
            val failures =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val workers =
                            List(4) { worker ->
                                async {
                                    val rng = Random(SEED + round * 31 + worker)
                                    // runCatching, not a catch: whatever stops the loop is the result.
                                    val thrown =
                                        runCatching {
                                            while (true) {
                                                yield() // the closer needs a thread of the pool too
                                                val s = db.prepare("SELECT ?, ?, hex(zeroblob(64))")
                                                try {
                                                    s.bindText(1, "w$worker\u0000\uD800")
                                                    s.bindBlob(2, rng.nextBytes(rng.nextInt(0, 256)))
                                                    while (s.step()) {
                                                        s.getText(0)
                                                        s.getBlob(1)
                                                        s.getText(2)
                                                    }
                                                    if (rng.nextBoolean()) s.reset()
                                                    if (rng.nextBoolean()) s.clearBindings()
                                                } finally {
                                                    s.close()
                                                }
                                            }
                                        }.exceptionOrNull()
                                    listOfNotNull(thrown)
                                }
                            }
                        val closer =
                            async {
                                delay(closeAfter)
                                db.close()
                            }
                        closer.await()
                        workers.awaitAll().flatten()
                    }
                }
            assertEquals(4, failures.size, "every worker must stop with exactly one failure")
            for (failure in failures) assertTrue(failure is SQLiteException, "round $round: $failure")
            val e = assertFailsWith<SQLiteException> { db.prepare("SELECT 1") }
            assertTrue("closed" in e.message.orEmpty(), e.message)
            db.close()
        }
    }

    @Test
    fun closeRacingAnInterruptibleQueryAndPreparesOfNewOnes() = runTest(timeout = 120.seconds) {
        val longQuery =
            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 300000000) SELECT count(*) FROM c"
        repeat(20) { round ->
            val random = Random(SEED + round)
            val db = rawOpen(":memory:", flags)
            val outcomes =
                // IO, not Default: the runner and the preparer block their threads in native code, and
                // on a two-core device Default has only two, so the closer would run only after the
                // whole query had finished and the race under test would never happen.
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val runner = async { runCatching { db.prepare(longQuery).use { it.step() } } }
                        val preparer =
                            async {
                                runCatching {
                                    while (true) {
                                        yield()
                                        db.prepare("SELECT 1").use { it.step() }
                                    }
                                }
                            }
                        val closer =
                            async {
                                delay(5 + random.nextLong(0, 20))
                                db.close()
                            }
                        closer.await()
                        listOf(runner.await(), preparer.await())
                    }
                }
            for (outcome in outcomes) {
                val failure = outcome.exceptionOrNull()
                assertTrue(failure is SQLiteException, "round $round: $outcome")
            }
        }
    }

    @Test
    fun closingStatementsAndTheConnectionAtTheSameTimeFinalizesEachOnce() = runTest(timeout = 120.seconds) {
        repeat(60) { round ->
            val random = Random(SEED + round)
            val db = rawOpen(":memory:", flags)
            val statements = List(16) { db.prepare("SELECT $it, ?") }
            val jobs =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val closers =
                            statements.shuffled(random).map { s ->
                                async {
                                    delay(random.nextLong(0, 4))
                                    s.close()
                                    s.close()
                                }
                            }
                        val steppers =
                            statements.map { s ->
                                async {
                                    runCatching {
                                        s.bindText(1, "x")
                                        s.step()
                                        s.reset()
                                    }
                                }
                            }
                        val dbCloser =
                            async {
                                delay(random.nextLong(0, 4))
                                db.close()
                            }
                        closers.awaitAll()
                        dbCloser.await()
                        steppers.awaitAll()
                    }
                }
            for (outcome in jobs) {
                val failure = outcome.exceptionOrNull()
                assertTrue(failure == null || failure is SQLiteException, "round $round: $outcome")
            }
            statements.forEach { assertFailsWith<SQLiteException> { it.step() } }
        }
    }

    private companion object {
        const val SEED = 20_260_102L
    }
}
