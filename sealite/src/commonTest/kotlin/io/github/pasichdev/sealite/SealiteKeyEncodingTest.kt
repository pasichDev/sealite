// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SealiteKeyEncodingTest {
    private fun bytesOf(vararg chars: Char): ByteArray = SealiteKey.passphrase(chars).use { it.keyBytes() }

    @Test
    fun passphrasesAreEncodedAsUtf8() {
        assertContentEquals(byteArrayOf(0x61), bytesOf('a'))
        assertContentEquals(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), bytesOf('é'))
        assertContentEquals(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()), bytesOf('€'))
        // Just above the surrogate block: an ordinary three-byte character.
        assertContentEquals(byteArrayOf(0xEE.toByte(), 0x80.toByte(), 0x80.toByte()), bytesOf('\uE000'))
        assertContentEquals(
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()),
            bytesOf('\uD83D', '\uDE00'),
        )
    }

    @Test
    fun anUnpairedSurrogateIsRejectedWhereverItSits() {
        val cases =
            mapOf(
                "a high surrogate at the end" to charArrayOf('a', '\uD83D'),
                "a high surrogate before a plain character" to charArrayOf('\uD83D', 'a'),
                "a high surrogate before a character past the low surrogates" to charArrayOf('\uD83D', '\uE000'),
                "a high surrogate before another high one" to charArrayOf('\uD83D', '\uD83D', '\uDE00'),
                "a low surrogate on its own" to charArrayOf('\uDE00'),
            )
        for ((name, chars) in cases) {
            val e = assertFailsWith<IllegalArgumentException>(name) { SealiteKey.passphrase(chars) }
            assertTrue(e.message.orEmpty().contains("unpaired surrogate"), name)
        }
    }

    @Test
    fun rawKeysWithASaltValidateEveryPart() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val salt = ByteArray(16) { (it + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(ByteArray(31) { 1 }, salt) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(ByteArray(32), salt) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(key, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { SealiteKey.raw(key, ByteArray(17)) }
        SealiteKey.raw(key, salt).use { assertEquals(2 + 96 + 1, it.keyBytes().size) }
    }

    @Test
    fun closingTwiceIsHarmless() {
        val key = rawKey(1)
        key.close()
        key.close()
        assertTrue(key.isClosed)
    }
}
