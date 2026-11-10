// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import io.github.pasichdev.sealite.internal.RawDb
import io.github.pasichdev.sealite.internal.exec
import io.github.pasichdev.sealite.internal.rawOpen
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hostile input across the native boundary: invalid and odd SQL, U+0000, unpaired surrogates, empty
 * and large values, paths that can not be opened. Whatever goes in, the answer is a value or a
 * [SQLiteException], never a crash and never a corrupted neighbour. The loops use fixed seeds and are
 * short; a seed that ever fails is added here as its own test.
 */
class BoundaryInputTest {
    private val flags = 0x02 or 0x04 // READWRITE | CREATE

    private fun <T> withRawDb(block: (RawDb) -> T): T {
        val db = rawOpen(":memory:", flags)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun RawDb.stillWorks() {
        prepare("SELECT 40 + 2").use {
            assertTrue(it.step())
            assertEquals(42L, it.getLong(0))
        }
    }

    // ---- invalid SQL ---------------------------------------------------------------------------

    @Test
    fun invalidSqlIsRefusedWithAnExceptionAndTheConnectionSurvives() = withRawDb { db ->
        val invalid =
            listOf(
                "SELEC 1",
                "SELECT * FROM",
                "SELECT * FROM no_such_table",
                "CREATE TABLE",
                "SELECT 'unterminated",
                "SELECT \"unterminated",
                "SELECT (1",
                "SELECT 1 +",
                "INSERT INTO",
                "PRAGMA no_such_pragma = (",
                "\u0001\u0002\u0003",
                "SELECT éé FROM é",
                "SELECT ?1 ?2 ?",
                "(((((((((((((((((((((((((((((((((",
                "SELECT " + "(".repeat(5000),
                "SELECT " + "1+".repeat(50_000) + "1", // deeper than SQLite's expression depth limit
            )
        for (sql in invalid) {
            val e = assertFailsWith<SQLiteException>("sql of length ${sql.length}") { db.prepare(sql) }
            assertTrue(e.message.orEmpty().startsWith("Error code: "), e.message)
        }
        db.stillWorks()
    }

    @Test
    fun sqlThatFailsOnlyWhenRunIsReportedByStepAndTheStatementCanBeReused() = withRawDb { db ->
        db.exec("CREATE TABLE u (v INTEGER UNIQUE)")
        db.exec("INSERT INTO u VALUES (1)")
        db.prepare("INSERT INTO u VALUES (?)").use {
            it.bindLong(1, 1L)
            assertFailsWith<SQLiteException> { it.step() }
            assertFailsWith<SQLiteException> { it.reset() }.also { e -> assertTrue("UNIQUE" in e.message.orEmpty()) }
            it.reset()
            it.bindLong(1, 2L)
            assertFalse(it.step())
        }
        db.prepare("SELECT count(*) FROM u").use {
            assertTrue(it.step())
            assertEquals(2L, it.getLong(0))
        }
        db.prepare("SELECT abs(-9223372036854775808)").use {
            assertFailsWith<SQLiteException> { it.step() } // integer overflow
        }
        db.stillWorks()
    }

    @Test
    fun sqlIsReadUpToTheFirstNulAndTheRestIsIgnored() = withRawDb { db ->
        db.prepare("SELECT 7\u0000 this is not sql at all (((").use {
            assertTrue(it.step())
            assertEquals(7L, it.getLong(0))
        }
        // Nothing before the NUL: no statement.
        assertFailsWith<SQLiteException> { db.prepare("\u0000SELECT 1") }
        assertFailsWith<SQLiteException> { db.prepare("\u0000") }
        db.stillWorks()
    }

    @Test
    fun unpairedSurrogatesInSqlTextNeverCrash() = withRawDb { db ->
        // SQL text is not sanitized (only bound text is). Either outcome is fine; the process must live.
        for (sql in listOf(
            "SELECT '\uD800'",
            "SELECT '\uDC00'",
            "SELECT '\uD800x'",
            "SELECT 1 -- \uD83D",
            "SELECT \uDE00",
        )) {
            try {
                db.prepare(sql).use { statement ->
                    if (statement.step()) {
                        for (i in 0 until statement.getColumnCount()) statement.getText(i)
                    }
                }
            } catch (_: SQLiteException) {
                // refused: fine
            }
        }
        db.stillWorks()
    }

    @Test
    fun aLargeSqlTextIsPrepared() = withRawDb { db ->
        val literal = "a".repeat(3 * 1024 * 1024)
        db.prepare("SELECT length('$literal')").use {
            assertTrue(it.step())
            assertEquals(literal.length.toLong(), it.getLong(0))
        }
        val many = (1..20_000).joinToString(separator = ",") { "$it" }
        db.prepare("SELECT count(*) FROM (SELECT 1 WHERE 1 IN ($many))").use {
            assertTrue(it.step())
        }
    }

    @Test
    fun aMutatedStatementIsEitherPreparedOrRefused() = withRawDb { db ->
        db.exec("CREATE TABLE m (a INTEGER, b TEXT, c BLOB)")
        val seeds =
            listOf(
                "INSERT INTO m (a, b, c) VALUES (?, ?, ?)",
                "SELECT a, b, c FROM m WHERE a > ? AND b LIKE ? ORDER BY c LIMIT 5",
                "UPDATE m SET b = upper(b) || 'x' WHERE a IN (1, 2, 3)",
                "WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM r WHERE x < 3) SELECT x FROM r",
                "CREATE INDEX IF NOT EXISTS mi ON m (a, b)",
                "SELECT json_extract('{\"k\": [1, 2]}', '$.k[1]'), hex(zeroblob(4)), printf('%d', 5)",
            )
        val random = Random(SEED)
        var prepared = 0
        var refused = 0
        repeat(600) {
            val chars = seeds[random.nextInt(seeds.size)].toCharArray().toMutableList()
            repeat(random.nextInt(1, 4)) {
                if (chars.isEmpty()) return@repeat
                val at = random.nextInt(chars.size)
                when (random.nextInt(4)) {
                    0 -> chars.removeAt(at)
                    1 -> chars.add(at, FUZZ_CHARS[random.nextInt(FUZZ_CHARS.length)])
                    2 -> chars[at] = FUZZ_CHARS[random.nextInt(FUZZ_CHARS.length)]
                    else -> while (chars.size > at) chars.removeAt(chars.size - 1)
                }
            }
            val sql = chars.joinToString("")
            try {
                db.prepare(sql).use { statement ->
                    prepared++
                    // Only look at the shape, never run it: a mutated DROP or DELETE is not the point.
                    statement.getColumnCount()
                }
            } catch (e: SQLiteException) {
                refused++
                assertTrue(e.message.orEmpty().startsWith("Error code: "), e.message)
            }
        }
        assertTrue(prepared > 0 && refused > 0, "prepared=$prepared refused=$refused")
        db.stillWorks()
    }

    // ---- U+0000 and unpaired surrogates in bound values ------------------------------------------

    @Test
    fun aBoundTextKeepsEveryNulAtItsPlace() = withRawDb { db ->
        db.exec("CREATE TABLE t (s TEXT)")
        val values =
            listOf(
                "\u0000",
                "\u0000\u0000\u0000",
                "\u0000a",
                "a\u0000",
                "a\u0000b\u0000c",
                "\u0000".repeat(1000),
                "x".repeat(70_000) + "\u0000" + "y".repeat(70_000),
                "😀\u0000😀",
                "\uD83D\u0000\uDE00",
            )
        for (value in values) {
            db.exec("DELETE FROM t")
            db.prepare("INSERT INTO t VALUES (?)").use {
                it.bindText(1, value)
                assertFalse(it.step())
            }
            val expected = value.withoutLoneSurrogates()
            db.prepare("SELECT s, length(CAST(s AS BLOB)) FROM t").use {
                assertTrue(it.step())
                assertEquals(expected, it.getText(0), "text of ${value.length} units")
                assertEquals(expected.encodeToByteArray().size.toLong(), it.getLong(1))
            }
        }
    }

    @Test
    fun aNulInANamedValueDoesNotCutTheNeighboursShort() = withRawDb { db ->
        db.prepare("SELECT ?, ?, ?").use {
            it.bindText(1, "before\u0000mid")
            it.bindBlob(2, byteArrayOf(0, 1, 0, 2, 0))
            it.bindText(3, "after")
            assertTrue(it.step())
            assertEquals("before\u0000mid", it.getText(0))
            assertContentEquals(byteArrayOf(0, 1, 0, 2, 0), it.getBlob(1))
            assertEquals("after", it.getText(2))
        }
    }

    @Test
    fun everyKindOfLoneSurrogateBecomesTheReplacementCharacter() = withRawDb { db ->
        val cases =
            mapOf(
                "\uD800" to "�",
                "\uDBFF" to "�",
                "\uDC00" to "�",
                "\uDFFF" to "�",
                "\uD800\uD800" to "��",
                "\uDC00\uD800" to "��", // low then high is not a pair
                "\uD800𐀀" to "�𐀀",
                "\uD800\u0000" to "�\u0000",
                "\u0000\uDC00" to "\u0000�",
                "ab\uD800" to "ab�",
                "\uDC00ab" to "�ab",
            )
        db.exec("CREATE TABLE s (v TEXT)")
        for ((input, expected) in cases) {
            db.exec("DELETE FROM s")
            db.prepare("INSERT INTO s VALUES (?)").use {
                it.bindText(1, input)
                assertFalse(it.step())
            }
            db.prepare("SELECT v FROM s").use {
                assertTrue(it.step())
                assertEquals(expected, it.getText(0), input.map { c -> c.code.toString(16) }.toString())
            }
        }
    }

    @Test
    fun randomTextWithNulAndSurrogatesRoundTripsAfterReplacement() = withRawDb { db ->
        val alphabet = listOf("a", "Z", " ", "\u0000", "é", "中", "\uD800", "\uDBFF", "\uDC00", "\uDFFF", "😀", "�")
        val random = Random(SEED)
        db.exec("CREATE TABLE f (id INTEGER PRIMARY KEY, v TEXT)")
        db.prepare("INSERT INTO f (v) VALUES (?)").use { insert ->
            repeat(400) {
                val value =
                    buildString { repeat(random.nextInt(0, 48)) { append(alphabet[random.nextInt(alphabet.size)]) } }
                insert.reset()
                insert.bindText(1, value)
                assertFalse(insert.step())
                db.prepare("SELECT v FROM f WHERE id = last_insert_rowid()").use {
                    assertTrue(it.step())
                    assertEquals(value.withoutLoneSurrogates(), it.getText(0))
                }
            }
        }
    }

    @Test
    fun randomBlobsRoundTrip() = withRawDb { db ->
        val random = Random(SEED)
        db.exec("CREATE TABLE r (id INTEGER PRIMARY KEY, v BLOB)")
        db.prepare("INSERT INTO r (v) VALUES (?)").use { insert ->
            repeat(200) {
                val value = random.nextBytes(random.nextInt(0, 600))
                insert.reset()
                insert.bindBlob(1, value)
                assertFalse(insert.step())
                db.prepare("SELECT v FROM r WHERE id = last_insert_rowid()").use {
                    assertTrue(it.step())
                    assertContentEquals(value, it.getBlob(0))
                }
            }
        }
    }

    // ---- empty and large values ----------------------------------------------------------------

    @Test
    fun valuesAroundTheSizesWhereBuffersChangeRoundTrip() = withRawDb { db ->
        db.exec("CREATE TABLE sz (b BLOB, s TEXT)")
        val sizes =
            listOf(
                0,
                1,
                2,
                3,
                255,
                256,
                257,
                4095,
                4096,
                4097,
                65_535,
                65_536,
                65_537,
                1_048_577,
                4 * 1024 * 1024 + 3,
            )
        for (size in sizes) {
            val blob = ByteArray(size) { (it xor (it ushr 8)).toByte() }
            val text = CharArray(size) { 'a' + (it % 26) }.concatToString()
            db.exec("DELETE FROM sz")
            db.prepare("INSERT INTO sz VALUES (?, ?)").use {
                it.bindBlob(1, blob)
                it.bindText(2, text)
                assertFalse(it.step())
            }
            db.prepare("SELECT b, s, typeof(b), typeof(s), length(b), length(s) FROM sz").use {
                assertTrue(it.step())
                assertContentEquals(blob, it.getBlob(0), "blob of $size")
                assertEquals(text, it.getText(1), "text of $size")
                assertEquals("blob", it.getText(2))
                assertEquals("text", it.getText(3))
                assertEquals(size.toLong(), it.getLong(4))
                assertEquals(size.toLong(), it.getLong(5))
            }
        }
    }

    @Test
    fun emptyAndNullAreFourDifferentThingsAndNoneOfThemCrashes() = withRawDb { db ->
        db.prepare("SELECT ?, ?, ?, ?").use {
            it.bindBlob(1, ByteArray(0))
            it.bindText(2, "")
            it.bindNull(3)
            it.bindBlob(4, ByteArray(1))
            assertTrue(it.step())
            assertEquals(listOf(4, 3, 5, 4), (0..3).map(it::getColumnType))
            assertContentEquals(ByteArray(0), it.getBlob(0))
            assertEquals("", it.getText(1))
            assertContentEquals(ByteArray(0), it.getBlob(2)) // NULL reads as an empty blob
            assertEquals("", it.getText(2))
            assertEquals(0L, it.getLong(2))
            assertEquals(0.0, it.getDouble(2))
            assertContentEquals(ByteArray(1), it.getBlob(3))
        }
    }

    // ---- statement misuse ----------------------------------------------------------------------

    @Test
    fun columnAndParameterIndexesOutOfRangeThrow() = withRawDb { db ->
        db.prepare("SELECT ?1, ?2").use {
            for (bad in listOf(0, -1, 3, 1000, Int.MAX_VALUE, Int.MIN_VALUE)) {
                assertFailsWith<SQLiteException>("bind $bad") { it.bindLong(bad, 1L) }
                assertFailsWith<SQLiteException>("bind $bad") { it.bindDouble(bad, 1.0) }
                assertFailsWith<SQLiteException>("bind $bad") { it.bindText(bad, "x") }
                assertFailsWith<SQLiteException>("bind $bad") { it.bindBlob(bad, ByteArray(3)) }
                assertFailsWith<SQLiteException>("bind $bad") { it.bindNull(bad) }
            }
            it.bindLong(1, 1L)
            it.bindLong(2, 2L)
            assertTrue(it.step())
            for (bad in listOf(-1, 2, 1000, Int.MAX_VALUE, Int.MIN_VALUE)) {
                assertFailsWith<SQLiteException>("column $bad") { it.getLong(bad) }
                assertFailsWith<SQLiteException>("column $bad") { it.getText(bad) }
                assertFailsWith<SQLiteException>("column $bad") { it.getBlob(bad) }
                assertFailsWith<SQLiteException>("column $bad") { it.getDouble(bad) }
                assertFailsWith<SQLiteException>("column $bad") { it.isNull(bad) }
                assertFailsWith<SQLiteException>("column $bad") { it.getColumnType(bad) }
                assertFailsWith<SQLiteException>("column $bad") { it.getColumnName(bad) }
            }
            assertEquals(1L, it.getLong(0))
            assertEquals(2L, it.getLong(1))
        }
    }

    @Test
    fun readingBeforeTheFirstRowOrAfterTheLastThrows() = withRawDb { db ->
        db.prepare("SELECT 1").use {
            assertFailsWith<SQLiteException> { it.getLong(0) }
            assertFailsWith<SQLiteException> { it.getText(0) }
            assertFailsWith<SQLiteException> { it.getBlob(0) }
            assertFailsWith<SQLiteException> { it.getDouble(0) }
            assertFailsWith<SQLiteException> { it.isNull(0) }
            assertFailsWith<SQLiteException> { it.getColumnType(0) }
            assertEquals("1", it.getColumnName(0)) // names need no row
            assertTrue(it.step())
            assertEquals(1L, it.getLong(0))
            assertFalse(it.step())
            assertFailsWith<SQLiteException> { it.getLong(0) }
            assertFailsWith<SQLiteException> { it.getText(0) }
            assertFailsWith<SQLiteException> { it.getBlob(0) }
        }
    }

    @Test
    fun bindingToAStatementThatIsMidwayThroughItsRowsIsRefusedUntilReset() = withRawDb { db ->
        db.prepare("SELECT ?1 UNION ALL SELECT ?1 + 1").use {
            it.bindLong(1, 10L)
            assertTrue(it.step())
            assertFailsWith<SQLiteException> { it.bindLong(1, 99L) }
            assertEquals(10L, it.getLong(0)) // the failed bind changed nothing
            assertTrue(it.step())
            assertEquals(11L, it.getLong(0))
            it.reset()
            it.bindLong(1, 20L)
            assertTrue(it.step())
            assertEquals(20L, it.getLong(0))
        }
    }

    @Test
    fun resetAndClearBindingsInEveryStateAreHarmless() = withRawDb { db ->
        db.prepare("SELECT ?1 UNION ALL SELECT 2").use {
            it.reset() // fresh
            it.clearBindings()
            it.reset()
            it.bindText(1, "kept until cleared")
            assertTrue(it.step())
            it.reset() // mid-run
            it.reset() // twice
            it.clearBindings()
            assertTrue(it.step())
            assertTrue(it.isNull(0)) // cleared
            assertTrue(it.step())
            assertFalse(it.step()) // done
            it.reset() // after done
            it.clearBindings()
            assertTrue(it.step()) // runs again from the top
        }
    }

    /** Regression: the row of a running statement may point at a bound value, which clearing freed. */
    @Test
    fun clearingBindingsWhileARowIsCurrentEndsTheRunInsteadOfLeavingTheRowDangling() = withRawDb { db ->
        db.prepare("SELECT ?1, ?2").use {
            it.bindText(1, "text that must not be read after it was freed ".repeat(8))
            it.bindBlob(2, ByteArray(512) { b -> b.toByte() })
            assertTrue(it.step())
            it.clearBindings()
            assertFailsWith<SQLiteException> { it.getText(0) }
            assertFailsWith<SQLiteException> { it.getBlob(1) }
            assertTrue(it.step()) // runs again from the top, now with nothing bound
            assertTrue(it.isNull(0))
            assertTrue(it.isNull(1))
        }
        db.stillWorks()
    }

    @Test
    fun manyStatementsOpenAtOnceAreFinalizedInAnyOrder() = withRawDb { db ->
        val random = Random(SEED)
        val statements = List(64) { db.prepare("SELECT $it, ?") }
        statements.forEachIndexed { i, s ->
            s.bindText(1, "v$i")
            if (i % 3 != 0) assertTrue(s.step())
        }
        for (s in statements.shuffled(random)) {
            s.close()
            s.close()
        }
        statements.forEach { assertFailsWith<SQLiteException> { it.step() } }
        db.stillWorks()
    }

    private fun randomCall(s: androidx.sqlite.SQLiteStatement, random: Random) {
        when (random.nextInt(11)) {
            0, 1 -> s.step()
            2 -> s.reset()
            3 -> s.clearBindings()
            4 -> s.bindLong(random.nextInt(-1, 3), 5L)
            5 -> s.bindText(random.nextInt(-1, 3), "t\u0000\uD800")
            6 -> s.getText(random.nextInt(-1, 3))
            7 -> s.getBlob(random.nextInt(-1, 3))
            8 -> s.isNull(random.nextInt(-1, 3))
            9 -> s.close()
            else -> s.getColumnName(random.nextInt(-1, 3))
        }
    }

    @Test
    fun scriptedOperationsOnStatementsAndAConnectionNeverEndInAnythingButAnSqliteException() {
        val sqls =
            listOf(
                "SELECT ?",
                "SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3",
                "SELEC nope",
                "",
                "SELECT x FROM nowhere",
                "SELECT zeroblob(1000)",
            )
        val random = Random(SEED)
        repeat(30) { round ->
            val db = rawOpen(":memory:", flags)
            val statements = mutableListOf<androidx.sqlite.SQLiteStatement>()
            var connectionClosed = false
            repeat(150) {
                val outcome =
                    runCatching {
                        when (random.nextInt(14)) {
                            0, 1 -> {
                                statements += db.prepare(sqls[random.nextInt(sqls.size)])
                            }

                            2 -> {
                                db.inTransaction()
                            }

                            3 -> {
                                if (random.nextInt(25) == 0) {
                                    db.close()
                                    connectionClosed = true
                                }
                            }

                            else -> {
                                statements.randomOrNull(random)?.let { randomCall(it, random) }
                            }
                        }
                    }
                val failure = outcome.exceptionOrNull()
                if (failure != null) assertTrue(failure is SQLiteException, "round $round: $failure")
                if (connectionClosed) {
                    assertFailsWith<SQLiteException> { db.prepare("SELECT 1") }
                }
            }
            db.close()
            statements.forEach { assertFailsWith<SQLiteException> { it.step() } }
        }
    }

    // ---- paths ---------------------------------------------------------------------------------

    @Test
    fun aPathThatIsTooLongIsRefusedByTheOpen() {
        for (length in listOf(300, 5_000, 70_000)) {
            val path = "/" + "d".repeat(length) + "/x.db"
            assertFailsWith<SQLiteException>("path of $length") { rawOpen(path, flags) }
        }
        val name = newTempDatabasePath().let { it + "-" + "n".repeat(400) }
        try {
            assertFailsWith<SQLiteException> { rawOpen(name, flags) } // a file name above NAME_MAX
        } finally {
            deleteDatabaseFiles(name)
        }
    }

    @Test
    fun aLongButLegalFileNameOpensAndWorks() {
        val base = newTempDatabasePath()
        val path = base + "-" + "n".repeat(120)
        try {
            SealiteDriver(rawKey(3)).use { driver ->
                driver.open(path).use { it.createNotes("long name") }
                driver.open(path).use { assertEquals(listOf("long name"), it.notes()) }
            }
        } finally {
            deleteDatabaseFiles(path)
            deleteDatabaseFiles(base)
        }
    }

    @Test
    fun aPathWithAnEmbeddedNulIsRefusedAndNoTruncatedFileIsCreated() {
        val base = newTempDatabasePath()
        try {
            val withNul = base + "\u0000" + "-tail"
            assertFailsWith<SQLiteException> { rawOpen(withNul, flags) }.also {
                assertTrue("NUL" in it.message.orEmpty(), it.message)
            }
            assertNull(readFileBytes(base), "a path cut at its NUL must not have been created")
            // Through the driver too: whatever it throws, nothing may appear at the cut path.
            val outcome = runCatching { SealiteDriver(rawKey(4)).use { it.open(withNul).close() } }
            assertTrue(outcome.isFailure, "the driver opened a path with a NUL")
            assertNull(readFileBytes(base), "a path cut at its NUL must not have been created")
        } finally {
            deleteDatabaseFiles(base)
        }
    }

    @Test
    fun anEmptyPathIsAnAnonymousTemporaryDatabaseNotAFileNamedNothing() {
        // SQLite's own rule, kept: "" opens a private temporary database. It must work and vanish.
        val db = rawOpen("", flags)
        try {
            db.exec("CREATE TABLE e (x)")
            db.stillWorks()
        } finally {
            db.close()
        }
    }

    private fun String.withoutLoneSurrogates(): String {
        val out = StringBuilder()
        var i = 0
        while (i < length) {
            val c = this[i]
            when {
                c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                    out.append(c).append(this[i + 1])
                    i++
                }

                c.isSurrogate() -> {
                    out.append('�')
                }

                else -> {
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    private companion object {
        /** Fixed: a failure is reproduced by the same run, and a failing seed becomes its own test. */
        const val SEED = 20_260_101

        const val FUZZ_CHARS = " ()',;\"*?%-/=+0123456789abcSELCTFROMWHERE\u0000\uD800é"
    }
}
