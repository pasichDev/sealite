// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SealiteDriverTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    private fun rawKey(seed: Int) = SealiteKey.raw(ByteArray(32) { (it + seed).toByte() })

    private fun SQLiteConnection.scalarText(sql: String): String? = prepare(sql).use {
        if (it.step()) it.getText(0) else null
    }

    private fun SQLiteConnection.notes(): List<String> = prepare("SELECT body FROM note ORDER BY id").use {
        buildList { while (it.step()) add(it.getText(0)) }
    }

    private fun SQLiteConnection.createNotes(vararg bodies: String) {
        execSQL("CREATE TABLE note (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
        for (body in bodies) {
            prepare("INSERT INTO note (body) VALUES (?)").use {
                it.bindText(1, body)
                it.step()
            }
        }
    }

    @Test
    fun roundTripAndReopenWithSameKey() {
        val path = newPath()
        SealiteDriver(rawKey(1)).use { driver ->
            driver.open(path).use { db ->
                db.createNotes("one", "two", "three")
                assertEquals(listOf("one", "two", "three"), db.notes())
            }
            driver.open(path).use { db ->
                assertEquals(listOf("one", "two", "three"), db.notes())
            }
        }
        // And a second driver instance with an equal key.
        SealiteDriver(rawKey(1)).use { driver ->
            driver.open(path).use { db -> assertEquals(3, db.notes().size) }
        }
    }

    @Test
    fun wrongKeyIsRejectedAndTheFileIsUntouched() {
        val path = newPath()
        SealiteDriver(rawKey(1)).use { driver ->
            driver.open(path).use { it.createNotes("secret") }
        }
        val before = assertNotNull(readFileBytes(path))

        SealiteDriver(rawKey(2)).use { driver ->
            val e = assertFailsWith<SealiteException> { driver.open(path) }
            assertEquals(SealiteError.WRONG_KEY, e.error, "message was: ${e.message}")
        }

        val after = assertNotNull(readFileBytes(path))
        assertEquals(before.size, after.size)
        assertContentEquals(before, after)
    }

    @Test
    fun walModeNeverWritesPlaintext() {
        val path = newPath()
        val marker = "SEALITE-MARKER-1234"
        SealiteDriver(rawKey(3)).use { driver ->
            driver.open(path).use { db ->
                db.prepare("PRAGMA journal_mode=WAL").use { it.step() }
                db.execSQL("CREATE TABLE note (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
                repeat(300) {
                    db.prepare("INSERT INTO note (body) VALUES (?)").use { s ->
                        s.bindText(1, marker)
                        s.step()
                    }
                }
                assertEquals(300, db.notes().count { it == marker })

                // While the connection is open the WAL still holds the writes.
                val main = assertNotNull(readFileBytes(path))
                val wal = readFileBytes("$path-wal")
                assertNotNull(wal, "expected a -wal file in WAL mode")
                assertTrue(wal.isNotEmpty(), "the -wal file is empty")
                val journal = readFileBytes("$path-journal")

                val markerBytes = marker.encodeToByteArray()
                assertFalse(main.contains(markerBytes), "marker found in the main file")
                assertFalse(wal.contains(markerBytes), "marker found in the -wal file")
                if (journal != null) assertFalse(journal.contains(markerBytes), "marker found in the -journal file")
                assertFalse(main.startsWith(SQLITE_HEADER), "main file has the plaintext SQLite header")
            }
        }
        val main = assertNotNull(readFileBytes(path))
        assertFalse(main.contains(marker.encodeToByteArray()), "marker found after close")
        assertFalse(main.startsWith(SQLITE_HEADER))
    }

    @Test
    fun passphraseKeyRoundTrip() {
        val path = newPath()
        val passphrase = "correct horse battery staple Ünï".toCharArray()
        SealiteDriver(SealiteKey.passphrase(passphrase)).use { driver ->
            driver.open(path).use { it.createNotes("p") }
        }
        SealiteDriver(SealiteKey.passphrase("correct horse battery staple Ünï".toCharArray())).use { driver ->
            driver.open(path).use { assertEquals(listOf("p"), it.notes()) }
        }
        SealiteDriver(SealiteKey.passphrase("wrong".toCharArray())).use { driver ->
            assertFailsWith<SealiteException> { driver.open(path) }
        }
        assertFalse(assertNotNull(readFileBytes(path)).startsWith(SQLITE_HEADER))
    }

    @Test
    fun rawKeyWithSaltRoundTrip() {
        val path = newPath()
        val key = ByteArray(32) { (it * 3).toByte() }
        val salt = ByteArray(16) { (it + 100).toByte() }
        SealiteDriver(SealiteKey.raw(key, salt)).use { driver ->
            driver.open(path).use { it.createNotes("salted") }
        }
        SealiteDriver(SealiteKey.raw(key, salt)).use { driver ->
            driver.open(path).use { assertEquals(listOf("salted"), it.notes()) }
        }
    }

    @Test
    fun checkReportsMissingOkWrongKeyAndNotEncrypted() {
        val path = newPath()
        val key = rawKey(5)
        assertEquals(Sealite.CheckResult.Missing, Sealite.check(path, key))

        SealiteDriver(key).use { driver ->
            driver.open(path).use { it.createNotes("x") }
        }
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, key))
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(path, rawKey(6)))

        val plain = newPath()
        writeFileBytes(plain, PLAINTEXT_DATABASE)
        assertEquals(Sealite.CheckResult.NotEncrypted, Sealite.check(plain, key))
        assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(plain), "check() must not change the file")

        val empty = newPath()
        writeFileBytes(empty, ByteArray(0))
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(empty, key))
    }

    @Test
    fun rekeyReplacesTheKey() {
        val path = newPath()
        val oldKey = rawKey(7)
        val newKey = rawKey(8)
        SealiteDriver(oldKey).use { driver ->
            driver.open(path).use { it.createNotes("kept") }
        }
        Sealite.rekey(path, oldKey, newKey)

        SealiteDriver(oldKey).use { driver ->
            val e = assertFailsWith<SealiteException> { driver.open(path) }
            assertEquals(SealiteError.WRONG_KEY, e.error)
        }
        SealiteDriver(newKey).use { driver ->
            driver.open(path).use { assertEquals(listOf("kept"), it.notes()) }
        }
    }

    @Test
    fun versionsReportTheLinkedLibrary() {
        val v = Sealite.versions
        assertTrue(v.sqlcipher.isNotEmpty(), "sqlcipher version is empty")
        assertTrue(v.sqlite.isNotEmpty())
        assertTrue(v.sealite.isNotEmpty())
        if (isApplePlatform) assertEquals("commoncrypto", v.cryptoProvider.lowercase())
    }

    @Test
    fun openAfterCloseThrows() {
        val path = newPath()
        val driver = SealiteDriver(rawKey(9))
        driver.close()
        assertFailsWith<IllegalStateException> { driver.open(path) }
        assertEquals(null, readFileBytes(path), "a closed driver must not create the file")
    }

    @Test
    fun keyFactoriesValidateTheirInput() {
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(ByteArray(32), ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.passphrase(CharArray(0)) }
    }
}
