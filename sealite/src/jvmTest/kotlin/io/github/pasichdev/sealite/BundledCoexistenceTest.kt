// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sealite and androidx.sqlite's own bundled SQLite load two different native libraries into one
 * JVM. Both must work, and only the Sealite file may be encrypted.
 */
class BundledCoexistenceTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
    }

    @Test
    fun aPlainBundledDatabaseAndAnEncryptedOneWorkInTheSameJvm() {
        // androidx.sqlite:sqlite-bundled ships no library for Intel macOS (Sealite does).
        val intelMac = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "x86_64"
        assumeTrue(!intelMac, "sqlite-bundled has no osx_x64 native library")
        val plainPath = newTempDatabasePath().also { paths += it }
        val sealedPath = newTempDatabasePath().also { paths += it }

        val bundled = BundledSQLiteDriver().open(plainPath)
        try {
            bundled.createNotes("plain-marker-1")
            assertEquals(listOf("plain-marker-1"), bundled.notes())
            // The bundled library is a stock SQLite: no cipher.
            assertEquals(null, bundled.scalar("PRAGMA cipher_version")?.takeIf { it.isNotEmpty() })

            withDb(sealedPath, rawKey(1)) { sealed ->
                sealed.createNotes("sealed-marker-1")
                assertEquals(listOf("sealed-marker-1"), sealed.notes())
                assertTrue(sealed.scalar("PRAGMA cipher_version").orEmpty().isNotEmpty())
                // And they interleave.
                bundled.prepare("INSERT INTO note (body) VALUES ('plain-marker-2')").use { it.step() }
                assertEquals(listOf("plain-marker-1", "plain-marker-2"), bundled.notes())
            }
        } finally {
            bundled.close()
        }

        val plain = assertNotNull(readFileBytes(plainPath))
        val sealed = assertNotNull(readFileBytes(sealedPath))
        assertTrue(plain.startsWith(SQLITE_HEADER), "the bundled file is a plain SQLite database")
        assertTrue(plain.contains("plain-marker-1".encodeToByteArray()))
        assertFalse(sealed.startsWith(SQLITE_HEADER), "the Sealite file must be encrypted")
        assertFalse(sealed.contains("sealed-marker-1".encodeToByteArray()))
        assertEquals(Sealite.CheckResult.NotEncrypted, Sealite.check(plainPath, rawKey(1)))
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(sealedPath, rawKey(1)))
        assertEquals(listOf("sealed-marker-1"), withDb(sealedPath, rawKey(1)) { it.notes() })
    }
}
