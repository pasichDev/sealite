// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.systemsqlite.sqlite3_compileoption_used
import io.github.pasichdev.sealite.systemsqlite.sqlite3_libversion
import kotlinx.cinterop.toKString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves Sealite's renamed SQLCipher and the operating system's own libsqlite3 live in one binary:
 * the system calls bind to the system library (no codec), Sealite's to its own (codec present).
 */
class SystemSqliteCoexistenceTest {
    private var path: String? = null

    @AfterTest
    fun cleanUp() {
        path?.let(::deleteDatabaseFiles)
    }

    @Test
    fun systemSqliteAndSealiteCoexist() {
        val system = sqlite3_libversion()!!.toKString()
        assertTrue(system.isNotEmpty())
        assertEquals(0, sqlite3_compileoption_used("HAS_CODEC"), "the system SQLite must not be SQLCipher")

        val dbPath = newTempDatabasePath().also { path = it }
        SealiteDriver(SealiteKey.raw(ByteArray(32) { (it + 7).toByte() })).use { driver ->
            driver.open(dbPath).use { db ->
                val cipher = db.prepare("PRAGMA cipher_version").use { if (it.step()) it.getText(0) else "" }
                assertTrue(cipher.isNotEmpty(), "Sealite's SQLCipher must answer cipher_version")
                assertEquals(cipher, Sealite.versions.sqlcipher)
            }
        }
        assertTrue(readFileBytes(dbPath)?.startsWith(SQLITE_HEADER) != true, "the file must be encrypted")
    }
}
