// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.internal.wipe
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The key a [SealiteDriver] opens databases with.
 *
 * There are two kinds, and they are not interchangeable: a [raw] key is the 256-bit database key
 * itself and skips key derivation, a [passphrase] key is stretched by SQLCipher's PBKDF2. The same
 * bytes given to both forms open different keys, so a file written with one is never readable with
 * the other.
 *
 * Every factory copies its input: the caller keeps, and wipes, its own array. [close] wipes the
 * copy held here; a closed key can not be used again. There is deliberately no `String`
 * overload: a `String` can not be wiped.
 *
 * ```
 * val key = SealiteKey.raw(keyFromKeystore)
 * keyFromKeystore.fill(0)
 * val driver = SealiteDriver(key) // copies the key
 * key.close()
 * ```
 */
public class SealiteKey private constructor(
    private var material: ByteArray?,
    internal val kind: Kind,
) : AutoCloseable {
    internal enum class Kind { RAW, PASSPHRASE }

    // Guards [material]: a copy is never taken from an array that [close] is wiping, so a
    // concurrent close can make an open fail, never key a database with zeroed bytes.
    private val lock = SynchronizedObject()

    /**
     * The bytes handed to `sqlite3_key_v2`: the raw-key literal `x'<hex>'` for [raw] keys, the
     * UTF-8 passphrase otherwise. A fresh copy on every call; the caller wipes it.
     *
     * @throws IllegalStateException if the key is closed.
     */
    internal fun keyBytes(): ByteArray = synchronized(lock) {
        val bytes = material ?: throw IllegalStateException("SealiteKey is closed")
        bytes.copyOf()
    }

    internal val isClosed: Boolean get() = synchronized(lock) { material == null }

    internal fun copy(): SealiteKey = SealiteKey(keyBytes(), kind)

    override fun close() {
        synchronized(lock) {
            material?.wipe()
            material = null
        }
    }

    public companion object {
        private const val RAW_KEY_SIZE = 32
        private const val SALT_SIZE = 16
        private const val ZERO: Byte = 0

        /**
         * A 256-bit key used as is, with no key derivation (SQLCipher's raw-key form). The
         * recommended choice: SQLCipher runs no PBKDF2 per connection, and a key from a KDF or a
         * key store is already uniform. The salt is read from the file header.
         *
         * @param key exactly 32 bytes.
         * @throws IllegalArgumentException if [key] is not 32 bytes long or is all zeros (an
         *   uninitialized buffer, never a real key).
         */
        public fun raw(key: ByteArray): SealiteKey {
            require(key.size == RAW_KEY_SIZE) { "A raw key is $RAW_KEY_SIZE bytes, got ${key.size}" }
            require(key.any { it != ZERO }) { "A raw key of all zero bytes is not a key" }
            return SealiteKey(rawLiteral(key, salt = null), Kind.RAW)
        }

        /**
         * A 256-bit key plus the database's 16-byte salt, for files whose salt is not stored in
         * the file header.
         *
         * @param key exactly 32 bytes.
         * @param salt exactly 16 bytes.
         * @throws IllegalArgumentException if [key] or [salt] has the wrong length, or [key] is all
         *   zeros.
         */
        public fun raw(key: ByteArray, salt: ByteArray): SealiteKey {
            require(key.size == RAW_KEY_SIZE) { "A raw key is $RAW_KEY_SIZE bytes, got ${key.size}" }
            require(key.any { it != ZERO }) { "A raw key of all zero bytes is not a key" }
            require(salt.size == SALT_SIZE) { "A salt is $SALT_SIZE bytes, got ${salt.size}" }
            return SealiteKey(rawLiteral(key, salt), Kind.RAW)
        }

        /**
         * A passphrase that SQLCipher stretches with PBKDF2 on every connection open. Slower than
         * [raw] (Room opens up to five connections); prefer [raw] when the app owns the key.
         *
         * The passphrase is encoded as UTF-8. SQLCipher itself reads a passphrase that has the
         * form `x'<hex>'` as a raw key, so never build a passphrase from untrusted input.
         *
         * @throws IllegalArgumentException if [passphrase] is empty (an empty key would leave the
         *   database unencrypted) or is not well-formed UTF-16 (an unpaired surrogate has no UTF-8
         *   form, and other SQLCipher implementations would encode it differently).
         */
        public fun passphrase(passphrase: CharArray): SealiteKey {
            require(passphrase.isNotEmpty()) { "An empty passphrase would leave the database unencrypted" }
            return SealiteKey(utf8(passphrase), Kind.PASSPHRASE)
        }

        /** `x'` + hex(key) [+ hex(salt)] + `'`, built without any intermediate `String`. */
        private fun rawLiteral(key: ByteArray, salt: ByteArray?): ByteArray {
            val hexLength = (key.size + (salt?.size ?: 0)) * 2
            val out = ByteArray(hexLength + 3)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            var at = 2
            for (b in key) at = putHex(out, at, b)
            if (salt != null) for (b in salt) at = putHex(out, at, b)
            out[at] = '\''.code.toByte()
            return out
        }

        private fun putHex(out: ByteArray, at: Int, b: Byte): Int {
            val v = b.toInt() and 0xFF
            out[at] = HEX[v ushr 4]
            out[at + 1] = HEX[v and 0x0F]
            return at + 2
        }

        private val HEX = "0123456789ABCDEF".encodeToByteArray()

        /**
         * UTF-8 of [chars] without a `String` in between. Surrogate pairs are encoded as one code
         * point; an unpaired surrogate is rejected.
         */
        private fun utf8(chars: CharArray): ByteArray {
            val buffer = ByteArray(chars.size * 4)
            var n = 0
            var i = 0
            while (i < chars.size) {
                var cp = chars[i].code
                if (cp in 0xD800..0xDBFF && i + 1 < chars.size && chars[i + 1].code in 0xDC00..0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) shl 10) + (chars[i + 1].code - 0xDC00)
                    i++
                } else if (cp in 0xD800..0xDFFF) {
                    buffer.wipe()
                    throw IllegalArgumentException("The passphrase has an unpaired surrogate at index $i")
                }
                when {
                    cp < 0x80 -> {
                        buffer[n++] = cp.toByte()
                    }

                    cp < 0x800 -> {
                        buffer[n++] = (0xC0 or (cp shr 6)).toByte()
                        buffer[n++] = (0x80 or (cp and 0x3F)).toByte()
                    }

                    cp < 0x10000 -> {
                        buffer[n++] = (0xE0 or (cp shr 12)).toByte()
                        buffer[n++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                        buffer[n++] = (0x80 or (cp and 0x3F)).toByte()
                    }

                    else -> {
                        buffer[n++] = (0xF0 or (cp shr 18)).toByte()
                        buffer[n++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                        buffer[n++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                        buffer[n++] = (0x80 or (cp and 0x3F)).toByte()
                    }
                }
                i++
            }
            return try {
                buffer.copyOf(n)
            } finally {
                buffer.wipe()
            }
        }
    }
}
