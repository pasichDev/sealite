// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CheckTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    @Test
    fun plaintextIsNotEncryptedAndStaysUntouched() {
        val path = newPath()
        writeFileBytes(path, PLAINTEXT_DATABASE)
        assertEquals(Sealite.CheckResult.NotEncrypted, Sealite.check(path, rawKey(1)))
        assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(path))
        assertEquals(null, readFileBytes("$path-wal"))
    }

    @Test
    fun anEmptyFileIsOk() {
        val path = newPath()
        writeFileBytes(path, ByteArray(0))
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, rawKey(1)))
        assertEquals(0, readFileBytes(path)?.size)
    }

    @Test
    fun aMissingFileIsMissingAndIsNotCreated() {
        val path = newPath()
        assertEquals(Sealite.CheckResult.Missing, Sealite.check(path, rawKey(1)))
        assertEquals(null, readFileBytes(path))
    }

    @Test
    fun randomBytesAreWrongKey() {
        val path = newPath()
        val random = Random(42).nextBytes(8192)
        writeFileBytes(path, random)
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(path, rawKey(1)))
        assertContentEquals(random, readFileBytes(path))
    }

    @Test
    fun theRightKeyIsOkAndAnotherIsWrongKey() {
        val path = newPath()
        withDb(path, rawKey(1)) { it.createNotes("a") }
        assertEquals(Sealite.CheckResult.Ok, Sealite.check(path, rawKey(1)))
        assertEquals(Sealite.CheckResult.WrongKey, Sealite.check(path, rawKey(2)))
    }
}
