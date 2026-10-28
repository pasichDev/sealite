// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

import androidx.sqlite.execSQL
import io.github.pasichdev.sealite.Sealite
import io.github.pasichdev.sealite.SealiteDriver
import io.github.pasichdev.sealite.SealiteKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a user of the released artifact does first: open an encrypted database and read it back. */
class SmokeTest {
    private fun key() = SealiteKey.raw(ByteArray(32) { (it + 7).toByte() })

    @Test
    fun theReleasedArtifactOpensAnEncryptedDatabase() {
        val dir = Files.createTempDirectory("sealite-smoke")
        val path = dir.resolve("smoke.db").toString()
        try {
            SealiteDriver(key()).use { driver ->
                driver.open(path).use { db ->
                    db.execSQL("CREATE TABLE note (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
                    db.execSQL("INSERT INTO note (body) VALUES ('smoke-marker')")
                    val cipher = db.prepare("PRAGMA cipher_version").use { it.step(); it.getText(0) }
                    assertTrue(cipher.isNotEmpty(), "SQLCipher must be linked in")
                }
            }

            val bytes = Files.readAllBytes(dir.resolve("smoke.db"))
            assertFalse(
                bytes.copyOf(16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)),
                "the file must not start with the plain SQLite header",
            )
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("smoke-marker"), "the data must not be readable")

            assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, key()))
            assertEquals(
                Sealite.CheckResult.WrongKey,
                Sealite.check(path, SealiteKey.raw(ByteArray(32) { 1 })),
            )
            assertEquals(System.getProperty("expected.version"), Sealite.versions.sealite)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
