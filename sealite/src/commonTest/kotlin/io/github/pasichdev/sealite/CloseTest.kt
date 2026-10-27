// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
