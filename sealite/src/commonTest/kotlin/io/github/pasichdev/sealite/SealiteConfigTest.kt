// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.SealiteConfig.Compatibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SealiteConfigTest {
    @Test
    fun defaultIsSqlcipher4WithNoOverrides() {
        val c = SealiteConfig.DEFAULT
        assertEquals(Compatibility.SQLCIPHER_4, c.compatibility)
        assertNull(c.kdfIterations)
        assertNull(c.pageSize)
        assertEquals(c, SealiteConfig {})
        assertEquals(c, SealiteConfig.builder().build())
    }

    @Test
    fun validation() {
        assertFailsWith<IllegalArgumentException> { SealiteConfig { kdfIterations = 0 } }
        assertFailsWith<IllegalArgumentException> { SealiteConfig { kdfIterations = -5 } }
        for (bad in listOf(0, 1, 256, 511, 513, 1000, 3000, 65537, 131072, -4096)) {
            assertFailsWith<IllegalArgumentException>("pageSize $bad") { SealiteConfig { pageSize = bad } }
        }
        for (good in listOf(512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)) {
            assertEquals(good, SealiteConfig { pageSize = good }.pageSize)
        }
        assertEquals(1, SealiteConfig { kdfIterations = 1 }.kdfIterations)
    }

    @Test
    fun equalsAndHashCodeFollowTheValues() {
        val a =
            SealiteConfig {
                compatibility = Compatibility.SQLCIPHER_3
                kdfIterations = 1000
                pageSize = 2048
            }
        val b =
            SealiteConfig
                .builder()
                .compatibility(
                    Compatibility.SQLCIPHER_3,
                ).kdfIterations(1000)
                .pageSize(2048)
                .build()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.toString(), b.toString())
        assertNotEquals(a, SealiteConfig.DEFAULT)
        assertNotEquals(
            a,
            SealiteConfig {
                compatibility = Compatibility.SQLCIPHER_3
                kdfIterations = 1000
                pageSize =
                    4096
            },
        )
        assertNotEquals<Any>(a, "not a config")
    }

    @Test
    fun toBuilderCopiesAndChangesAreIndependent() {
        val a =
            SealiteConfig {
                kdfIterations = 500
                pageSize = 8192
            }
        val copy = a.toBuilder().build()
        assertEquals(a, copy)
        val changed = a.toBuilder().pageSize(null).build()
        assertNull(changed.pageSize)
        assertEquals(500, changed.kdfIterations)
        assertEquals(8192, a.pageSize, "the original must not change")
        assertSame(a.compatibility, copy.compatibility)
    }

    @Test
    fun javaStyleBuilderChainsAndLaterCallsWin() {
        val c =
            SealiteConfig
                .builder()
                .pageSize(1024)
                .pageSize(2048)
                .kdfIterations(10)
                .compatibility(Compatibility.SQLCIPHER_3)
                .build()
        assertEquals(2048, c.pageSize)
        assertEquals(10, c.kdfIterations)
        assertEquals(Compatibility.SQLCIPHER_3, c.compatibility)
    }

    @Test
    fun aBuilderCanBeReusedAfterBuild() {
        val builder = SealiteConfig.builder().pageSize(1024)
        val first = builder.build()
        builder.pageSize(2048)
        assertEquals(1024, first.pageSize)
        assertEquals(2048, builder.build().pageSize)
    }

    @Test
    fun aNonDefaultConfigRoundTripsAFile() {
        val path = newTempDatabasePath()
        try {
            val config =
                SealiteConfig {
                    pageSize = 8192
                    kdfIterations = 2000
                }
            val passphrase = { SealiteKey.passphrase("pp".toCharArray()) }
            withDb(path, passphrase(), config) { it.createNotes("cfg") }
            assertEquals(listOf("cfg"), withDb(path, passphrase(), config) { it.notes() })
            // The same key under other settings does not open the file.
            assertEquals(SealiteError.WRONG_KEY, openErrorWith(path, passphrase(), SealiteConfig.DEFAULT))
        } finally {
            deleteDatabaseFiles(path)
        }
    }

    private fun openErrorWith(path: String, key: SealiteKey, config: SealiteConfig): SealiteError? = try {
        withDb(path, key, config) {}
        null
    } catch (e: androidx.sqlite.SQLiteException) {
        Sealite.errorOf(e)
    }
}
