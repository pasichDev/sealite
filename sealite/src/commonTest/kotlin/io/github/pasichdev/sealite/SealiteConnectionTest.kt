// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SealiteConnectionTest {
    @Test
    fun aClosedConnectionRefusesEveryCallAndClosesQuietlyAgain() {
        val driver = SealiteDriver(rawKey(1))
        try {
            val connection = driver.open(":memory:")
            assertFalse(connection.inTransaction())
            connection.close()
            connection.close()
            assertFailsWith<SQLiteException> { connection.prepare("SELECT 1") }
            assertFailsWith<SQLiteException> { connection.inTransaction() }
        } finally {
            driver.close()
        }
    }

    @Test
    fun aConnectionInsideATransactionSaysSo() {
        withDb(":memory:", rawKey(1)) { db ->
            db.prepare("BEGIN").use { it.step() }
            assertTrue(db.inTransaction())
        }
    }
}
