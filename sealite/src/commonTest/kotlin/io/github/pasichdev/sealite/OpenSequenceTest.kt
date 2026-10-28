// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import io.github.pasichdev.sealite.internal.OpenSequence
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The decisions [OpenSequence] takes on what the native library answers, with a scripted [FakeRawDb]:
 * a real SQLCipher does not misbehave on demand.
 */
class OpenSequenceTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    private val schema = "SELECT count(*) FROM sqlite_schema"

    private fun healthy(vararg overrides: Pair<String, Answer>): FakeRawDb = FakeRawDb(
        mapOf(
            "PRAGMA cipher_version" to Answer.Row("4.12.0 community"),
            schema to Answer.Row("0"),
            "PRAGMA cipher_status" to Answer.Row("1"),
        ) + overrides,
    )

    private fun errorOfVerify(db: FakeRawDb): SealiteException = assertFailsWith { OpenSequence.verify(db) }

    @Test
    fun aHealthyHandleVerifies() {
        OpenSequence.verify(healthy())
    }

    @Test
    fun aMissingOrEmptyCipherVersionMeansNoEncryption() {
        for (answer in listOf(Answer.NoRow, Answer.Row(null), Answer.Row(""))) {
            val e = errorOfVerify(healthy("PRAGMA cipher_version" to answer))
            assertEquals(SealiteError.PROVIDER_MISSING, e.error)
        }
    }

    @Test
    fun aNotADatabaseCodeIsAWrongKey() {
        // The primary code is the low byte: 26 is SQLITE_NOTADB, 538 an extended form of it.
        for (code in listOf(26, 538)) {
            val failing = Answer.Fails(SQLiteException("Error code: $code, message: file is not a database"))
            val e = errorOfVerify(healthy(schema to failing))
            assertEquals(SealiteError.WRONG_KEY, e.error)
            assertSame(failing.error, e.cause)
        }
    }

    @Test
    fun anyOtherSchemaFailureIsRethrownAsIs() {
        for (message in listOf("Error code: 5, message: busy", "no code in here", "Error code: 99999999999")) {
            val original = SQLiteException(message)
            val thrown =
                assertFailsWith<SQLiteException> { OpenSequence.verify(healthy(schema to Answer.Fails(original))) }
            assertSame(original, thrown)
            assertEquals(null, Sealite.errorOf(thrown))
        }
    }

    @Test
    fun aCodecThatIsNotInUseIsRefused() {
        for (answer in listOf(Answer.Row("0"), Answer.Row(null), Answer.NoRow)) {
            val e = errorOfVerify(healthy("PRAGMA cipher_status" to answer))
            assertEquals(SealiteError.NOT_KEYED, e.error)
        }
    }

    @Test
    fun anAllZeroKeyIsRefusedBeforeItReachesTheNativeCall() {
        // A one-character NUL passphrase encodes to a single zero byte: the same bytes a key that
        // was wiped by close() while a connection was opening would show.
        val db = healthy()
        SealiteKey.passphrase(charArrayOf('\u0000')).use { key ->
            val e = assertFailsWith<IllegalStateException> { OpenSequence.applyKey(db, key) }
            assertTrue(e.message.orEmpty().contains("wiped"), e.message)
        }
        assertEquals(0, db.keyCalls)
        OpenSequence.applyKey(db, rawKey(3))
        assertEquals(1, db.keyCalls)
    }

    @Test
    fun onlyAnExistingPlaintextFileIsAPlaintextDatabase() {
        for (name in listOf(":memory:", "", "file:memdb1?mode=memory")) {
            assertFalse(OpenSequence.isPlaintextDatabase(name), name)
        }
        val path = newTempDatabasePath().also { paths += it }
        assertFalse(OpenSequence.isPlaintextDatabase(path), "no file")
        writeFileBytes(path, SQLITE_HEADER + ByteArray(100))
        assertTrue(OpenSequence.isPlaintextDatabase(path))
        writeFileBytes(path, ByteArray(100) { 7 })
        assertFalse(OpenSequence.isPlaintextDatabase(path))
    }

    @Test
    fun theErrorCodeIsReadFromTheMessage() {
        assertEquals(26, OpenSequence.errorCodeOf("Error code: 26, message: file is not a database"))
        assertEquals(538, OpenSequence.errorCodeOf("prefix Error code: 538"))
        assertEquals(null, OpenSequence.errorCodeOf(null))
        assertEquals(null, OpenSequence.errorCodeOf("no code here"))
        assertEquals(null, OpenSequence.errorCodeOf("Error code: 99999999999"))
    }
}
