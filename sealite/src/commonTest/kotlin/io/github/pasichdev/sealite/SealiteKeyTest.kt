// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SealiteKeyTest {
    private val paths = mutableListOf<String>()

    private fun newPath(): String = newTempDatabasePath().also { paths += it }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    @Test
    fun anAllZeroRawKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(ByteArray(32), ByteArray(16) { 1 }) }
        // One non-zero byte is a key.
        SealiteKey.raw(ByteArray(32).also { it[31] = 1 }).close()
        // A zero salt is allowed: the salt is not a secret and may legitimately be anything.
        SealiteKey.raw(ByteArray(32) { 1 }, ByteArray(16)).close()
    }

    @Test
    fun aLoneSurrogateIsRejected() {
        assertFailsWith<IllegalArgumentException> { SealiteKey.passphrase(charArrayOf('a', '\uD83D')) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.passphrase(charArrayOf('\uDE00', 'a')) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.passphrase(charArrayOf('\uD83D', 'x', '\uDE00')) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.passphrase(charArrayOf('\uDC00', '\uD800')) }
    }

    @Test
    fun anEmojiPassphraseOpensTheDatabaseItCreated() {
        val path = newPath()
        val emoji = "🔐 ключ 鍵"
        withDb(path, SealiteKey.passphrase(emoji.toCharArray())) { it.createNotes("e") }
        assertEquals(listOf("e"), withDb(path, SealiteKey.passphrase(emoji.toCharArray())) { it.notes() })
        // The same text with the emoji replaced by its surrogates written out is the same key.
        val explicit = charArrayOf('\uD83D', '\uDD10') + " ключ 鍵".toCharArray()
        assertEquals(listOf("e"), withDb(path, SealiteKey.passphrase(explicit)) { it.notes() })
        assertEquals(SealiteError.WRONG_KEY, openError(path, SealiteKey.passphrase("🔑 ключ 鍵".toCharArray())))
    }

    @Test
    fun theFactoriesCopyTheirInput() {
        val path = newPath()
        val bytes = ByteArray(32) { (it + 1).toByte() }
        val key = SealiteKey.raw(bytes)
        bytes.fill(0)
        withDb(path, key) { it.createNotes("copy") }
        val chars = "secret".toCharArray()
        val pass = SealiteKey.passphrase(chars)
        chars.fill('x')
        val other = newPath()
        withDb(other, pass) { it.createNotes("copy") }
        assertEquals(listOf("copy"), withDb(other, SealiteKey.passphrase("secret".toCharArray())) { it.notes() })
    }

    @Test
    fun aClosedKeyCanNotBeUsed() {
        val path = newPath()
        val key = rawKey(1)
        val driver = SealiteDriver(key)
        key.close() // the driver holds its own copy
        driver.open(path).use { it.createNotes("still works") }
        driver.close()
        assertFailsWith<IllegalStateException> { driver.open(path) }
        assertFailsWith<IllegalStateException> { Sealite.check(path, key) }
    }

    @Test
    fun closingTheDriverWhileManyThreadsOpenNeverKeysADatabaseWithZeros() = runTest {
        // A passphrase of the form x'<hex>' is read by SQLCipher as a raw key: this one is the
        // all-zero key a wiped buffer would produce.
        val zeroKeyText = "x'" + "0".repeat(64) + "'"
        val rounds = 6
        val perRound = 24
        var successes = 0
        repeat(rounds) {
            val real = rawKey(7)
            val files = List(perRound) { newPath() }
            val driver = SealiteDriver(real)
            val outcomes =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val opens =
                            files.map { file ->
                                async {
                                    try {
                                        driver.open(file).use { db -> db.createNotes("n") }
                                        true
                                    } catch (_: IllegalStateException) {
                                        false // closed before or while opening
                                    } catch (_: SQLiteException) {
                                        // A failed open is acceptable, a silent wrong key is not:
                                        // it must be a reported, typed failure of the open.
                                        false
                                    }
                                }
                            }
                        val closer = async { driver.close() }
                        val results = opens.awaitAll()
                        closer.await()
                        results
                    }
                }
            successes += outcomes.count { it }
            files.zip(outcomes).forEach { (file, ok) ->
                val zero = { SealiteKey.passphrase(zeroKeyText.toCharArray()) }
                if (ok) {
                    assertEquals(Sealite.CheckResult.Ok, Sealite.check(file, rawKey(7)), "opened with the real key")
                    assertEquals(
                        Sealite.CheckResult.WrongKey,
                        Sealite.check(file, zero()),
                        "must not open with a zero key",
                    )
                } else {
                    // Nothing keyed with zeros may exist; an unwritten file is empty or missing.
                    val bytes = readFileBytes(file)
                    if (bytes != null && bytes.isNotEmpty()) {
                        assertEquals(Sealite.CheckResult.Ok, Sealite.check(file, rawKey(7)))
                    }
                    assertTrue(Sealite.check(file, zero()) != Sealite.CheckResult.NotEncrypted)
                }
            }
            real.close()
        }
        assertNotNull(successes) // outcomes vary with scheduling; every one was checked above
    }
}
