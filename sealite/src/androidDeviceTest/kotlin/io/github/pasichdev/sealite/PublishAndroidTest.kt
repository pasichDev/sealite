// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import android.os.Build
import android.system.Os
import android.system.OsConstants
import io.github.pasichdev.sealite.internal.publishWithoutReplacing
import io.github.pasichdev.sealite.internal.renameNoReplace
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** publishWithoutReplacing on a device: renameat2 from API 30, the locked check and rename below. */
class PublishAndroidTest {
    private val dirs = mutableListOf<File>()

    private fun newDir(): File = File(newTempDatabasePath()).parentFile!!.also { dirs += it }

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.listFiles()?.forEach(File::delete) }
        dirs.forEach(File::delete)
    }

    private fun file(dir: File, name: String, vararg bytes: Byte) = File(dir, name).also { it.writeBytes(bytes) }

    @Test
    fun publishMovesTheFileAndKeepsAnExistingTarget() {
        val dir = newDir()
        val from = file(dir, "from", 1, 2, 3)
        val to = File(dir, "to")
        publishWithoutReplacing(from.path, to.path)
        assertFalse(from.exists())
        assertContentEquals(byteArrayOf(1, 2, 3), to.readBytes())

        val second = file(dir, "second", 9)
        assertFailsWith<IllegalStateException> { publishWithoutReplacing(second.path, to.path) }
        assertContentEquals(byteArrayOf(1, 2, 3), to.readBytes())
        assertContentEquals(byteArrayOf(9), second.readBytes())
    }

    @Test
    fun aDanglingSymlinkAtTheTargetIsNotReplaced() {
        val dir = newDir()
        val from = file(dir, "from", 1)
        val to = File(dir, "to")
        Os.symlink(File(dir, "nowhere").path, to.path)
        assertFailsWith<IllegalStateException> { publishWithoutReplacing(from.path, to.path) }
        assertTrue(OsConstants.S_ISLNK(Os.lstat(to.path).st_mode), "the symlink must stay")
        assertEquals(File(dir, "nowhere").path, Os.readlink(to.path))
        assertContentEquals(byteArrayOf(1), from.readBytes())
    }

    @Test
    fun renameat2ReportsEexistAndNeverReplacesOnApi30() {
        if (Build.VERSION.SDK_INT < 30) return
        val dir = newDir()
        val from = file(dir, "from", 1)
        val to = File(dir, "to")
        assertEquals(0, renameNoReplace(from.path, to.path))
        assertContentEquals(byteArrayOf(1), to.readBytes())
        val again = file(dir, "again", 2)
        assertEquals(OsConstants.EEXIST, renameNoReplace(again.path, to.path))
        assertContentEquals(byteArrayOf(1), to.readBytes())
        assertEquals(OsConstants.ENOENT, renameNoReplace(File(dir, "missing").path, File(dir, "x").path))
    }

    @Test
    fun concurrentPublishesToOneTargetLeaveExactlyOneWinner() {
        repeat(5) { round ->
            val dir = newDir()
            val to = File(dir, "to$round")
            val sources = List(8) { file(dir, "from$it", it.toByte()) }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(sources.size)
            try {
                val outcomes =
                    sources.map { source ->
                        pool.submit<Throwable?> {
                            start.await()
                            runCatching { publishWithoutReplacing(source.path, to.path) }.exceptionOrNull()
                        }
                    }
                start.countDown()
                val failures = outcomes.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, failures.count { it == null }, "failures: $failures")
                assertTrue(failures.filterNotNull().all { it is IllegalStateException }, "failures: $failures")
            } finally {
                pool.shutdownNow()
            }
            val winner = sources.single { !it.exists() }
            assertContentEquals(byteArrayOf(winner.name.removePrefix("from").toByte()), to.readBytes())
            assertEquals(sources.size - 1, sources.count { it.exists() })
        }
    }
}
