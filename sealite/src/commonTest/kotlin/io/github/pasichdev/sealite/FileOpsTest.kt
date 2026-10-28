// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.internal.createUniqueFileNextTo
import io.github.pasichdev.sealite.internal.databaseFiles
import io.github.pasichdev.sealite.internal.deleteFileIfExists
import io.github.pasichdev.sealite.internal.publishWithoutReplacing
import io.github.pasichdev.sealite.internal.readFileHeader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOpsTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    @Test
    fun theDatabaseFilesAreTheMainFileAndItsCompanions() {
        assertEquals(listOf("a", "a-journal", "a-wal", "a-shm"), databaseFiles("a"))
    }

    @Test
    fun deleteRemovesAFileAndIgnoresAMissingOne() {
        val path = newPath()
        deleteFileIfExists(path) // nothing there yet
        writeFileBytes(path, byteArrayOf(1))
        deleteFileIfExists(path)
        assertNull(readFileBytes(path))
    }

    @Test
    fun deleteFailsWhenTheFileCanNotBeRemoved() {
        val path = newPath()
        writeFileBytes(path, byteArrayOf(1))
        // The directory holds a file, so removing the directory fails.
        assertFails { deleteFileIfExists(parentOf(path)) }
    }

    @Test
    fun publishMovesTheFileAndNeverReplacesAnExistingTarget() {
        val from = newPath()
        val to = "$from.published"
        paths += to
        writeFileBytes(from, byteArrayOf(1, 2, 3))
        publishWithoutReplacing(from, to)
        assertNull(readFileBytes(from))
        assertContentEquals(byteArrayOf(1, 2, 3), readFileBytes(to))

        writeFileBytes(from, byteArrayOf(9))
        assertFailsWith<IllegalStateException> { publishWithoutReplacing(from, to) }
        assertContentEquals(byteArrayOf(1, 2, 3), readFileBytes(to), "the target must be untouched")
        assertContentEquals(byteArrayOf(9), readFileBytes(from))
    }

    @Test
    fun uniqueFilesAreNewEmptyAndDistinctNextToTheTarget() {
        val target = newPath()
        val first = createUniqueFileNextTo(target).also { paths += it }
        val second = createUniqueFileNextTo(target).also { paths += it }
        assertNotEquals(first, second)
        assertContentEquals(ByteArray(0), readFileBytes(first))
        assertEquals(parentOf(target), parentOf(first))
        assertTrue(nameOf(first).startsWith(".${nameOf(target)}.sealite-"))
    }

    @Test
    fun readFileHeaderReturnsAtMostCountBytes() {
        val path = newPath()
        assertNull(readFileHeader(path, 4))
        writeFileBytes(path, byteArrayOf(1, 2, 3, 4, 5))
        assertContentEquals(byteArrayOf(1, 2, 3), readFileHeader(path, 3))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), readFileHeader(path, 100))
        writeFileBytes(path, ByteArray(0))
        assertContentEquals(ByteArray(0), readFileHeader(path, 4))
    }
}

// Test paths use '/' on POSIX and '\\' on Windows.
private fun separatorIndex(path: String): Int = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))

private fun parentOf(path: String): String = path.substring(0, separatorIndex(path))

private fun nameOf(path: String): String = path.substring(separatorIndex(path) + 1)
