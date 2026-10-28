// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** SQLCipher reads `x'<64|96|160 hex>'` as a raw key and skips PBKDF2: `passphrase` must refuse it. */
class PassphraseRawLiteralTest {
    private fun literal(prefix: String, digits: Int, digit: Char = 'a', suffix: String = "'") =
        prefix + digit.toString().repeat(digits) + suffix

    private fun refused(text: String) {
        assertFailsWith<IllegalArgumentException>(text.take(12)) { SealiteKey.passphrase(text.toCharArray()) }
    }

    private fun accepted(text: String) {
        SealiteKey.passphrase(text.toCharArray()).close()
    }

    @Test
    fun theRawKeyFormsAreRefusedInEitherCase() {
        for (digits in listOf(64, 96, 160)) {
            refused(literal("x'", digits))
            refused(literal("X'", digits))
            refused(literal("x'", digits, 'F'))
            refused(literal("X'", digits, '0'))
            refused("x'" + "0123456789abcdefABCDEF".repeat(10).take(digits) + "'")
        }
    }

    @Test
    fun otherLengthsAreOrdinaryPassphrases() {
        for (digits in listOf(0, 1, 62, 63, 65, 66, 128, 158, 162)) accepted(literal("x'", digits))
    }

    @Test
    fun aMalformedLiteralIsAnOrdinaryPassphrase() {
        accepted(literal("x'", 64, suffix = "")) // no closing quote
        accepted(literal("x'", 64, suffix = " ")) // trailing space
        accepted(literal("x", 64)) // no opening quote
        accepted(literal("y'", 64))
        accepted(literal("'", 64))
        accepted(literal("x'", 64, suffix = "x")) // right length, last character is not a quote
        accepted(literal("xa", 64)) // right length, second character is not a quote
        accepted(literal("x'", 64, 'g')) // not hex
        accepted("x'" + "a".repeat(63) + "g'")
        accepted("x'" + "a".repeat(32) + "-" + "a".repeat(31) + "'")
        accepted(" " + literal("x'", 64))
        accepted(literal("x'", 64) + "x")
    }
}
