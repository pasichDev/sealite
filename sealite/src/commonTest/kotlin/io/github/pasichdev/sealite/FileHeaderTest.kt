// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.internal.readFileHeader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/** readFileHeader: null means "no such file" and nothing else. */
class FileHeaderTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    @Test
    fun aMissingFileIsNullAndAnEmptyFileIsAnEmptyArray() {
        val path = newPath()
        assertNull(readFileHeader(path, 16))
        writeFileBytes(path, ByteArray(0))
        assertContentEquals(ByteArray(0), readFileHeader(path, 16))
        assertContentEquals(ByteArray(0), readFileHeader(path, 0))
    }

    @Test
    fun aFileThatExistsButCanNotBeReadThrowsInsteadOfLookingMissing() {
        val path = newPath()
        writeFileBytes(path, byteArrayOf(1, 2, 3))
        if (!makeUnreadable(path)) return // no permission bits here, or running as the superuser
        assertFails { readFileHeader(path, 16) }
    }

    @Test
    fun aDirectoryThrowsInsteadOfLookingMissing() {
        val path = newPath()
        val directory = path.substring(0, maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')))
        assertFails { readFileHeader(directory, 16) }
    }
}
