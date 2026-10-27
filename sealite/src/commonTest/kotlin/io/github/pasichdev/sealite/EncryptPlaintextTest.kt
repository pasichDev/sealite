// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptPlaintextTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    private fun assertNoTemp(encryptedPath: String) {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            assertNull(readFileBytes("$encryptedPath.sealite-tmp$suffix"), "temp file left: $suffix")
        }
    }

    private fun SQLiteConnection.schema(): List<String> = column(
        "SELECT type || '|' || name || '|' || tbl_name || '|' || coalesce(sql, '') " +
            "FROM sqlite_schema ORDER BY type, name",
    )

    private fun SQLiteConnection.items(): List<String> = column(
        "SELECT id || '|' || name || '|' || qty || '|' || hex(data) || ':' || typeof(data) FROM item ORDER BY id",
    )

    @Test
    fun theSimpleFixtureIsCopied() {
        val plain = newPath()
        val encrypted = newPath()
        writeFileBytes(plain, PLAINTEXT_DATABASE)
        Sealite.encryptPlaintext(plain, encrypted, rawKey(1))

        assertEquals(Sealite.CheckResult.Ok, Sealite.check(encrypted, rawKey(1)))
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(encrypted, rawKey(2)))
        assertFalse(assertNotNull(readFileBytes(encrypted)).startsWith(SQLITE_HEADER))
        withDb(encrypted, rawKey(1)) { db ->
            assertEquals(listOf("plain"), db.column("SELECT body FROM note"))
        }
        assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(plain), "the plaintext file must be left as it is")
        assertNoTemp(encrypted)
    }

    @Test
    fun theRichFixtureKeepsSchemaRowsAndPragmas() {
        val plain = newPath()
        val encrypted = newPath()
        writeFileBytes(plain, GOLDEN_PLAIN_RICH)

        // What the plaintext database says, read with the plaintext file's own header: compare
        // against the encrypted copy after the migration.
        val expectedSchema =
            listOf(
                "index|item_name|item|CREATE INDEX item_name ON item(name)",
                "index|item_name_qty|item|CREATE UNIQUE INDEX item_name_qty ON item(name, qty)",
                "table|audit|audit|CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT, msg TEXT)",
                "table|item|item|CREATE TABLE item(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                    "qty INTEGER NOT NULL DEFAULT 0, data BLOB)",
                "table|sqlite_sequence|sqlite_sequence|CREATE TABLE sqlite_sequence(name,seq)",
                "trigger|item_ai|item|CREATE TRIGGER item_ai AFTER INSERT ON item BEGIN INSERT INTO audit(msg) " +
                    "VALUES('added ' || new.name); END",
                "view|item_big|item_big|CREATE VIEW item_big AS SELECT id, name FROM item WHERE qty > 5",
            )

        Sealite.encryptPlaintext(plain, encrypted, rawKey(1))

        withDb(encrypted, rawKey(1)) { db ->
            assertEquals(expectedSchema, db.schema())
            assertEquals(listOf("1|apple|3|00FF10:blob", "2|Привіт 🌍|10|:blob", "3|cherry|7|:null"), db.items())
            assertEquals(
                listOf("1|added apple", "2|added Привіт 🌍", "3|added cherry"),
                db.column("SELECT id || '|' || msg FROM audit ORDER BY id"),
            )
            assertEquals(
                listOf("audit|3", "item|3"),
                db.column("SELECT name || '|' || seq FROM sqlite_sequence ORDER BY name"),
            )
            assertEquals("5", db.scalar("PRAGMA user_version"))
            assertEquals(0x12345678.toString(), db.scalar("PRAGMA application_id"))
            assertEquals("2", db.scalar("PRAGMA auto_vacuum"), "auto_vacuum must stay INCREMENTAL")
            assertEquals("ok", db.scalar("PRAGMA integrity_check"))
            assertEquals(
                listOf("2|Привіт 🌍"),
                db.column("SELECT id || '|' || name FROM item_big WHERE name LIKE 'Прив%'"),
            )

            // The trigger fires, AUTOINCREMENT continues after the copied sequence, the unique
            // index is enforced.
            db.execSQL("INSERT INTO item(name, qty) VALUES ('date', 1)")
            assertEquals("4", db.scalar("SELECT max(id) FROM item"))
            assertEquals("added date", db.scalar("SELECT msg FROM audit WHERE id = 4"))
            assertFailsWith<SQLiteException> { db.execSQL("INSERT INTO item(name, qty) VALUES ('date', 1)") }
        }
        val bytes = assertNotNull(readFileBytes(encrypted))
        assertFalse(bytes.startsWith(SQLITE_HEADER))
        assertFalse(bytes.contains("apple".encodeToByteArray()), "plaintext leaked into the encrypted file")
        assertFalse(bytes.contains("Привіт".encodeToByteArray()))
        assertContentEquals(GOLDEN_PLAIN_RICH, readFileBytes(plain), "the plaintext file must be left as it is")
        assertNoTemp(encrypted)
    }

    @Test
    fun aPassphraseKeyAndAnOtherPageSizeWork() {
        val plain = newPath()
        val encrypted = newPath()
        writeFileBytes(plain, GOLDEN_PLAIN_RICH)
        val config = SealiteConfig { pageSize = 8192 }
        val key = { SealiteKey.passphrase("pass".toCharArray()) }
        Sealite.encryptPlaintext(plain, encrypted, key(), config)
        withDb(encrypted, key(), config) { db ->
            assertEquals("8192", db.scalar("PRAGMA cipher_page_size"))
            assertEquals(3, db.items().size)
        }
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(encrypted, key()))
    }

    @Test
    fun anExistingEncryptedPathIsRefusedAndNothingChanges() {
        val plain = newPath()
        val encrypted = newPath()
        writeFileBytes(plain, PLAINTEXT_DATABASE)
        withDb(encrypted, rawKey(1)) { it.createNotes("mine") }
        val before = assertNotNull(readFileBytes(encrypted))

        assertFailsWith<IllegalArgumentException> { Sealite.encryptPlaintext(plain, encrypted, rawKey(2)) }
        assertContentEquals(before, readFileBytes(encrypted))
        assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(plain))
        assertNoTemp(encrypted)
        assertEquals(listOf("mine"), withDb(encrypted, rawKey(1)) { it.notes() })
    }

    @Test
    fun anExistingSidecarFileIsRefusedAndNothingChanges() {
        for (suffix in listOf("-wal", "-journal", "-shm")) {
            val plain = newPath()
            val encrypted = newPath()
            writeFileBytes(plain, PLAINTEXT_DATABASE)
            val sidecar = byteArrayOf(1, 2, 3)
            writeFileBytes(encrypted + suffix, sidecar)

            assertFailsWith<IllegalArgumentException>(suffix) { Sealite.encryptPlaintext(plain, encrypted, rawKey(1)) }
            assertNull(readFileBytes(encrypted), "the database must not be created ($suffix)")
            assertContentEquals(sidecar, readFileBytes(encrypted + suffix))
            assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(plain))
            assertNoTemp(encrypted)
        }
    }

    @Test
    fun aSourceThatIsNotPlaintextIsRefused() {
        val encryptedSource = newPath()
        withDb(encryptedSource, rawKey(1)) { it.createNotes("x") }
        val target = newPath()
        assertFailsWith<IllegalArgumentException> { Sealite.encryptPlaintext(encryptedSource, target, rawKey(2)) }
        assertFailsWith<IllegalArgumentException> { Sealite.encryptPlaintext(newPath(), target, rawKey(2)) }
        assertNull(readFileBytes(target))
        assertNoTemp(target)
    }

    @Test
    fun aBrokenPlaintextDatabaseFailsAndLeavesNoTemporaryFile() {
        val plain = newPath()
        val encrypted = newPath()
        // A valid header, then a page-sized run of garbage where the schema should be.
        val broken = GOLDEN_PLAIN_RICH.copyOf()
        for (i in 4096 until broken.size) broken[i] = 0x5A
        writeFileBytes(plain, broken)

        assertFailsWith<Throwable> { Sealite.encryptPlaintext(plain, encrypted, rawKey(1)) }
        assertNull(readFileBytes(encrypted), "a failed migration must not leave a database")
        assertNoTemp(encrypted)
        assertContentEquals(broken, readFileBytes(plain))
    }

    @Test
    fun aLeftoverTemporaryFileFromACrashIsReplaced() {
        val plain = newPath()
        val encrypted = newPath()
        writeFileBytes(plain, PLAINTEXT_DATABASE)
        writeFileBytes("$encrypted.sealite-tmp", byteArrayOf(9, 9, 9))
        Sealite.encryptPlaintext(plain, encrypted, rawKey(1))
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(encrypted, rawKey(1)))
        assertNoTemp(encrypted)
    }

    @Test
    fun theCopyIsUsableByTheDriverForWrites() {
        val plain = newPath()
        val encrypted = newPath()
        writeFileBytes(plain, PLAINTEXT_DATABASE)
        Sealite.encryptPlaintext(plain, encrypted, rawKey(1))
        withDb(encrypted, rawKey(1)) { it.execSQL("INSERT INTO note (body) VALUES ('after')") }
        assertTrue(withDb(encrypted, rawKey(1)) { it.notes() }.let { true })
    }
}
