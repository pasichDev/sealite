// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import io.github.pasichdev.sealite.internal.RawDb
import io.github.pasichdev.sealite.internal.exec
import io.github.pasichdev.sealite.internal.queryString
import io.github.pasichdev.sealite.internal.rawOpen
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The platform binding (JNI, cinterop) driven directly, on an unkeyed in-memory database. */
class RawDbTest {
    private val flags = 0x02 or 0x04 // READWRITE | CREATE

    private fun <T> withRawDb(block: (RawDb) -> T): T {
        val db = rawOpen(":memory:", flags)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun everyColumnTypeRoundTrips() = withRawDb { db ->
        db.exec("CREATE TABLE t (i INTEGER, r REAL, s TEXT, b BLOB, n)")
        db.prepare("INSERT INTO t VALUES (?, ?, ?, ?, ?)").use {
            it.bindLong(1, 42L)
            it.bindDouble(2, 1.5)
            it.bindText(3, "héllo")
            it.bindBlob(4, byteArrayOf(1, 2, 3))
            it.bindNull(5)
            assertFalse(it.step())
        }
        db.prepare("SELECT i, r, s, b, n FROM t").use {
            assertTrue(it.step())
            assertEquals(5, it.getColumnCount())
            assertEquals(listOf("i", "r", "s", "b", "n"), (0..4).map(it::getColumnName))
            assertEquals(listOf(1, 2, 3, 4, 5), (0..4).map(it::getColumnType))
            assertEquals(42L, it.getLong(0))
            assertEquals(1.5, it.getDouble(1))
            assertEquals("héllo", it.getText(2))
            assertContentEquals(byteArrayOf(1, 2, 3), it.getBlob(3))
            assertTrue(it.isNull(4))
            assertFalse(it.isNull(0))
            assertFalse(it.step())
        }
    }

    @Test
    fun resetRunsTheStatementAgainAndClearBindingsDropsTheValues() = withRawDb { db ->
        db.prepare("SELECT ?").use {
            it.bindLong(1, 7L)
            assertTrue(it.step())
            assertEquals(7L, it.getLong(0))
            it.reset()
            assertTrue(it.step())
            assertEquals(7L, it.getLong(0))
            it.reset()
            it.clearBindings()
            assertTrue(it.step())
            assertTrue(it.isNull(0))
        }
    }

    @Test
    fun aClosedStatementRefusesEveryCall() = withRawDb { db ->
        val statement = db.prepare("SELECT ?")
        statement.close()
        statement.close() // idempotent
        val calls: List<(SQLiteStatement) -> Unit> =
            listOf(
                { it.bindBlob(1, byteArrayOf(1)) },
                { it.bindDouble(1, 1.0) },
                { it.bindLong(1, 1L) },
                { it.bindText(1, "x") },
                { it.bindNull(1) },
                { it.getBlob(0) },
                { it.getDouble(0) },
                { it.getLong(0) },
                { it.getText(0) },
                { it.isNull(0) },
                { it.getColumnCount() },
                { it.getColumnName(0) },
                { it.getColumnType(0) },
                { it.step() },
                { it.reset() },
                { it.clearBindings() },
            )
        calls.forEachIndexed { index, call ->
            assertFailsWith<SQLiteException>("call #$index") { call(statement) }
        }
    }

    @Test
    fun aClosedHandleRefusesEveryCall() {
        val db = rawOpen(":memory:", flags)
        db.close()
        db.close() // idempotent
        assertFailsWith<SQLiteException> { db.prepare("SELECT 1") }
        assertFailsWith<SQLiteException> { db.inTransaction() }
        assertFailsWith<SQLiteException> { db.key(byteArrayOf(1)) }
        assertFailsWith<SQLiteException> { db.rekey(byteArrayOf(1)) }
    }

    @Test
    fun inTransactionFollowsBeginAndCommit() = withRawDb { db ->
        assertFalse(db.inTransaction())
        db.exec("BEGIN")
        assertTrue(db.inTransaction())
        db.exec("COMMIT")
        assertFalse(db.inTransaction())
    }

    @Test
    fun queryStringIsNullForNoRowAndForNull() = withRawDb { db ->
        assertNull(db.queryString("SELECT 1 WHERE 0"))
        assertNull(db.queryString("SELECT NULL"))
        assertEquals("1", db.queryString("SELECT 1"))
    }
}
