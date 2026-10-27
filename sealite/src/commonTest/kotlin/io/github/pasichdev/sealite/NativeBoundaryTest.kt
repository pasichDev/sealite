// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import io.github.pasichdev.sealite.internal.RawDb
import io.github.pasichdev.sealite.internal.exec
import io.github.pasichdev.sealite.internal.rawOpen
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What crosses the native boundary: text, blobs, names, messages and statements that are not statements. */
class NativeBoundaryTest {
    private val flags = 0x02 or 0x04 // READWRITE | CREATE

    private fun <T> withRawDb(block: (RawDb) -> T): T {
        val db = rawOpen(":memory:", flags)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    /** Binds [value] into a fresh one-row table and reads it back. */
    private fun RawDb.storeText(value: String): String {
        exec("DROP TABLE IF EXISTS t")
        exec("CREATE TABLE t (s TEXT NOT NULL)")
        prepare("INSERT INTO t VALUES (?)").use {
            it.bindText(1, value)
            assertFalse(it.step())
        }
        return prepare("SELECT s FROM t").use {
            assertTrue(it.step())
            it.getText(0)
        }
    }

    private fun RawDb.storedHex(): String = prepare("SELECT hex(CAST(s AS BLOB)) FROM t").use {
        assertTrue(it.step())
        it.getText(0)
    }

    @Test
    fun textRoundTripsWithEmbeddedNulAndNonBmpCharacters() = withRawDb { db ->
        for (value in listOf("a\u0000b", "\u0000", "", "x😀y", "\u0000\u0000", "héllo wörld", "日本語")) {
            assertEquals(value, db.storeText(value), "value of length ${value.length}")
        }
    }

    @Test
    fun anEmptyTextIsStoredAsEmptyTextNotNull() = withRawDb { db ->
        db.storeText("")
        db.prepare("SELECT typeof(s), length(s) FROM t").use {
            assertTrue(it.step())
            assertEquals("text", it.getText(0))
            assertEquals(0L, it.getLong(1))
        }
    }

    @Test
    fun aLoneSurrogateBecomesTheReplacementCharacterAndSwallowsNothing() = withRawDb { db ->
        assertEquals("a�b", db.storeText("a\uD800b"))
        assertEquals("61EFBFBD62", db.storedHex())
        assertEquals("ab�", db.storeText("ab\uD83D"))
        assertEquals("�x", db.storeText("\uDC00x"))
        assertEquals("��", db.storeText("\uD800\uD800"))
        assertEquals("a𐀀", db.storeText("a𐀀"))
        assertEquals("x😀y", db.storeText("x😀y"))
        assertEquals("78F09F988079", db.storedHex())
    }

    @Test
    fun getTextOnNullIsTheEmptyStringAndIsNullTellsThemApart() = withRawDb { db ->
        db.prepare("SELECT NULL, ''").use {
            assertTrue(it.step())
            assertEquals("", it.getText(0))
            assertTrue(it.isNull(0))
            assertEquals("", it.getText(1))
            assertFalse(it.isNull(1))
        }
    }

    @Test
    fun getTextReadsAnEmbeddedNulFromSql() = withRawDb { db ->
        db.prepare("SELECT 'x' || char(0) || 'y'").use {
            assertTrue(it.step())
            assertEquals("x\u0000y", it.getText(0))
        }
    }

    @Test
    fun aColumnNameOutsideTheBmpSurvives() = withRawDb { db ->
        db.prepare("SELECT 1 AS \"x😀y\", 2 AS \"é\"").use {
            assertEquals("x😀y", it.getColumnName(0))
            assertEquals("é", it.getColumnName(1))
        }
    }

    @Test
    fun anErrorMessageOutsideTheBmpSurvives() = withRawDb { db ->
        val e = assertFailsWith<SQLiteException> { db.prepare("SELECT * FROM \"😀\"") }
        assertTrue(e.message.orEmpty().contains("no such table: 😀"), e.message)
        assertTrue(e.message.orEmpty().startsWith("Error code: 1"), e.message)
    }

    @Test
    fun anEmptyBlobIsABlobNotNull() = withRawDb { db ->
        db.exec("CREATE TABLE b (v BLOB NOT NULL)")
        db.prepare("INSERT INTO b VALUES (?)").use {
            it.bindBlob(1, ByteArray(0))
            assertFalse(it.step())
        }
        db.prepare("SELECT typeof(v), length(v), v FROM b").use {
            assertTrue(it.step())
            assertEquals("blob", it.getText(0))
            assertEquals(0L, it.getLong(1))
            assertEquals(4, it.getColumnType(2)) // SQLITE_BLOB
            assertContentEquals(ByteArray(0), it.getBlob(2))
        }
    }

    @Test
    fun largeBlobAndTextRoundTrip() = withRawDb { db ->
        val blob = ByteArray(8 * 1024 * 1024) { (it * 31).toByte() }
        val text = "ab😀é".repeat(1_000_000) // 5 million UTF-16 units
        db.exec("CREATE TABLE big (b BLOB, s TEXT)")
        db.prepare("INSERT INTO big VALUES (?, ?)").use {
            it.bindBlob(1, blob)
            it.bindText(2, text)
            assertFalse(it.step())
        }
        db.prepare("SELECT b, s FROM big").use {
            assertTrue(it.step())
            assertContentEquals(blob, it.getBlob(0))
            assertEquals(text, it.getText(1))
        }
    }

    @Test
    fun aBindThatFailsLeavesTheStatementUsable() = withRawDb { db ->
        db.prepare("SELECT ?").use {
            assertFailsWith<SQLiteException> { it.bindText(2, "out of range") }
            assertFailsWith<SQLiteException> { it.bindBlob(2, byteArrayOf(1)) }
            assertFailsWith<SQLiteException> { it.bindBlob(2, ByteArray(0)) }
            it.bindText(1, "ok")
            assertTrue(it.step())
            assertEquals("ok", it.getText(0))
        }
    }

    @Test
    fun sqlThatCompilesToNothingIsRefusedNotWrappedInAStatement() = withRawDb { db ->
        for (sql in listOf("", " ", "   \n\t", "-- just a comment", "/* c */", ";", "; ;")) {
            val e = assertFailsWith<SQLiteException>("sql: '$sql'") { db.prepare(sql) }
            assertTrue(e.message.orEmpty().contains("no SQL statement"), e.message)
        }
        // The connection is still fine.
        db.prepare("SELECT 1").use { s: SQLiteStatement ->
            assertTrue(s.step())
        }
    }
}
