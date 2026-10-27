// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteConnection
import io.github.pasichdev.sealite.SealiteConfig.Compatibility
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Databases written by other SQLCipher implementations (see `testdata/README.md`) must open with
 * the documented key and configuration, read back the documented rows, accept writes, and reject
 * every other key.
 */
class GoldenFixturesTest {
    private class Fixture(
        val name: String,
        val bytes: ByteArray,
        val config: SealiteConfig,
        val key: () -> SealiteKey,
        val wrongKey: () -> SealiteKey,
    )

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    private fun rawKey() = SealiteKey.raw(ByteArray(32) { it.toByte() })

    private fun otherRawKey() = SealiteKey.raw(ByteArray(32) { (it + 1).toByte() })

    private fun passKey() = SealiteKey.passphrase(PASSPHRASE.toCharArray())

    private fun otherPassKey() = SealiteKey.passphrase("correct horse battery stapl3".toCharArray())

    private val fixtures =
        listOf(
            Fixture("cli_raw_v4", GOLDEN_CLI_RAW_V4, SealiteConfig.DEFAULT, ::rawKey, ::otherRawKey),
            Fixture("cli_raw_v4_wal", GOLDEN_CLI_RAW_V4_WAL, SealiteConfig.DEFAULT, ::rawKey, ::otherRawKey),
            Fixture("zetetic_raw_v4", GOLDEN_ZETETIC_RAW_V4, SealiteConfig.DEFAULT, ::rawKey, ::otherRawKey),
            Fixture("cli_pass_v4", GOLDEN_CLI_PASS_V4, SealiteConfig.DEFAULT, ::passKey, ::otherPassKey),
            Fixture(
                "cli_pass_v3",
                GOLDEN_CLI_PASS_V3,
                SealiteConfig { compatibility = Compatibility.SQLCIPHER_3 },
                ::passKey,
                ::otherPassKey,
            ),
        )

    private fun materialize(bytes: ByteArray): String {
        val path = newTempDatabasePath()
        paths += path
        writeFileBytes(path, bytes)
        return path
    }

    private class Note(
        val id: Long,
        val title: String,
        val body: ByteArray,
    )

    private fun SQLiteConnection.notes(): List<Note> = prepare("SELECT id, title, body FROM notes ORDER BY id").use {
        buildList {
            while (it.step()) {
                assertFalse(it.isNull(2), "body of row ${it.getLong(0)} is NULL")
                add(Note(it.getLong(0), it.getText(1), it.getBlob(2)))
            }
        }
    }

    private fun SQLiteConnection.userVersion(): Long = prepare("PRAGMA user_version").use {
        assertTrue(it.step())
        it.getLong(0)
    }

    private fun assertGoldenRows(rows: List<Note>) {
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertEquals("hello", rows[0].title)
        assertContentEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x10), rows[0].body)
        assertEquals("Привіт, світ 🌍", rows[1].title)
        assertContentEquals(ByteArray(4096) { (it % 251).toByte() }, rows[1].body)
        assertEquals("empty body", rows[2].title)
        assertEquals(0, rows[2].body.size)
    }

    @Test
    fun everyFixtureOpensAndReadsItsRows() {
        for (f in fixtures) {
            val path = materialize(f.bytes)
            SealiteDriver(f.key(), f.config).use { driver ->
                driver.open(path).use { db ->
                    assertGoldenRows(db.notes())
                    assertEquals(7L, db.userVersion(), "user_version of ${f.name}")
                }
            }
        }
    }

    @Test
    fun everyFixtureAcceptsAWriteAndReopens() {
        for (f in fixtures) {
            val path = materialize(f.bytes)
            SealiteDriver(f.key(), f.config).use { driver ->
                driver.open(path).use { db ->
                    db.prepare("INSERT INTO notes(title, body) VALUES (?, ?)").use {
                        it.bindText(1, "written by sealite")
                        it.bindBlob(2, byteArrayOf(1, 2, 3))
                        it.step()
                    }
                }
                driver.open(path).use { db ->
                    val rows = db.notes()
                    assertEquals(4, rows.size, "rows of ${f.name}")
                    assertGoldenRows(rows.take(3))
                    assertEquals("written by sealite", rows[3].title)
                    assertContentEquals(byteArrayOf(1, 2, 3), rows[3].body)
                    assertEquals(7L, db.userVersion())
                }
            }
            assertFalse(readFileBytes(path)!!.startsWith(SQLITE_HEADER), "${f.name} turned plaintext")
        }
    }

    @Test
    fun aWrongKeyIsRejectedForEveryFixture() {
        for (f in fixtures) {
            val path = materialize(f.bytes)
            SealiteDriver(f.wrongKey(), f.config).use { driver ->
                val e = assertFailsWith<SealiteException>(f.name) { driver.open(path) }
                assertEquals(SealiteError.WRONG_KEY, e.error, "${f.name}: ${e.message}")
            }
            assertContentEquals(f.bytes, readFileBytes(path), "${f.name} was modified by a failed open")
        }
    }

    @Test
    fun aVersion3FileIsRejectedUnderVersion4Defaults() {
        val path = materialize(GOLDEN_CLI_PASS_V3)
        SealiteDriver(passKey()).use { driver ->
            val e = assertFailsWith<SealiteException> { driver.open(path) }
            assertEquals(SealiteError.WRONG_KEY, e.error, e.message)
        }
    }

    @Test
    fun aRawKeySkipsTheKeyDerivation() {
        // The same 64 hex digits as a passphrase (no x'' wrapper) go through PBKDF2 and derive a
        // different key: the raw and the passphrase forms must never be interchangeable.
        val hex = ByteArray(32) { it.toByte() }.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val path = materialize(GOLDEN_CLI_RAW_V4)
        SealiteDriver(SealiteKey.passphrase(hex.toCharArray())).use { driver ->
            val e = assertFailsWith<SealiteException> { driver.open(path) }
            assertEquals(SealiteError.WRONG_KEY, e.error, e.message)
        }
        // Positive control: the raw form of the same key opens it.
        SealiteDriver(rawKey()).use { driver ->
            driver.open(path).use { assertEquals(3, it.notes().size) }
        }
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}
