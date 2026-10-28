// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SealiteErrorTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    @Test
    fun wrongKeyIsWrongKey() {
        val path = newPath()
        withDb(path, rawKey(1)) { it.createNotes("x") }
        val e = assertFailsWith<SealiteException> { withDb(path, rawKey(2)) {} }
        assertEquals(SealiteError.WRONG_KEY, e.error)
        assertEquals(SealiteError.WRONG_KEY, Sealite.errorOf(e))
    }

    @Test
    fun wrongKeyIsFoundThroughTheCauseChain() {
        val path = newPath()
        withDb(path, rawKey(1)) { it.createNotes("x") }
        val e = assertFailsWith<SealiteException> { withDb(path, rawKey(2)) {} }
        val wrapped = IllegalStateException("outer", RuntimeException("middle", e))
        assertEquals(SealiteError.WRONG_KEY, Sealite.errorOf(wrapped))
    }

    @Test
    fun aPlaintextDatabaseIsNotEncrypted() {
        val path = newPath()
        writeFileBytes(path, PLAINTEXT_DATABASE)
        val e = assertFailsWith<SealiteException> { withDb(path, rawKey(1)) {} }
        assertEquals(SealiteError.NOT_ENCRYPTED, e.error)
        // The file is left exactly as it was.
        kotlin.test.assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(path))
    }

    @Test
    fun otherFailuresAreNotSealiteErrors() {
        val path = newPath()
        withDb(path, rawKey(1)) { it.createNotes("x") }
        val syntax =
            assertFailsWith<SQLiteException> {
                withDb(path, rawKey(1)) { it.prepare("SELEKT nothing").use { s -> s.step() } }
            }
        assertNull(Sealite.errorOf(syntax))
        assertNull(Sealite.errorOf(RuntimeException("something else", IllegalStateException("deeper"))))
        assertNull(Sealite.errorOf(RuntimeException()))
    }

    @Test
    fun openingADirectoryThatDoesNotExistIsNotAKeyError() {
        val missing = newPath() + "/no/such/dir/x.db"
        val e = assertFailsWith<SQLiteException> { withDb(missing, rawKey(1)) {} }
        assertNull(Sealite.errorOf(e))
    }
}
