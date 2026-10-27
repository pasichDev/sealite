// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * How SQLCipher reads and writes the file. [DEFAULT] matches SQLCipher 4, so files are
 * interchangeable with `net.zetetic:sqlcipher-android` 4.x, SQLCipher for Apple platforms and the
 * `sqlcipher` command line tool.
 *
 * The configuration must describe the file: a database written with other settings fails to open
 * with [SealiteError.WRONG_KEY], exactly like a wrong key.
 *
 * ```
 * val legacy = SealiteConfig { compatibility = SealiteConfig.Compatibility.SQLCIPHER_3 }
 * // Java: SealiteConfig.builder().compatibility(Compatibility.SQLCIPHER_3).build()
 * ```
 *
 * Built only through [Builder], so options can be added in minor releases without breaking
 * binaries.
 */
public class SealiteConfig private constructor(
    /** The SQLCipher major version whose defaults apply. */
    public val compatibility: Compatibility,
    /**
     * PBKDF2 iterations for [SealiteKey.passphrase] keys; `null` keeps the [compatibility]
     * default. Raw keys do not run a key derivation. Lowering it weakens passphrase keys.
     */
    public val kdfIterations: Int?,
    /** Page size in bytes, a power of two from 512 to 65536; `null` keeps the [compatibility] default. */
    public val pageSize: Int?,
) {
    /** A copy of this configuration to change. */
    public fun toBuilder(): Builder = Builder()
        .compatibility(compatibility)
        .kdfIterations(kdfIterations)
        .pageSize(pageSize)

    override fun equals(other: Any?): Boolean = other is SealiteConfig &&
        compatibility == other.compatibility &&
        kdfIterations == other.kdfIterations &&
        pageSize == other.pageSize

    override fun hashCode(): Int = listOf(compatibility, kdfIterations, pageSize).hashCode()

    override fun toString(): String =
        "SealiteConfig(compatibility=$compatibility, kdfIterations=$kdfIterations, pageSize=$pageSize)"

    /** Builds a [SealiteConfig]; every option starts at the SQLCipher 4 default. */
    public class Builder {
        /** See [SealiteConfig.compatibility]. */
        public var compatibility: Compatibility = Compatibility.SQLCIPHER_4

        /** See [SealiteConfig.kdfIterations]. */
        public var kdfIterations: Int? = null

        /** See [SealiteConfig.pageSize]. */
        public var pageSize: Int? = null

        /** Sets [compatibility]. */
        public fun compatibility(value: Compatibility): Builder = apply { compatibility = value }

        /** Sets [kdfIterations]. */
        public fun kdfIterations(value: Int?): Builder = apply { kdfIterations = value }

        /** Sets [pageSize]. */
        public fun pageSize(value: Int?): Builder = apply { pageSize = value }

        /**
         * @throws IllegalArgumentException if [kdfIterations] is not positive or [pageSize] is not
         *   a power of two from 512 to 65536.
         */
        public fun build(): SealiteConfig {
            val iterations = kdfIterations
            val size = pageSize
            require(iterations == null || iterations > 0) { "kdfIterations must be positive" }
            require(size == null || (size in MIN_PAGE_SIZE..MAX_PAGE_SIZE && size and (size - 1) == 0)) {
                "pageSize must be a power of two between $MIN_PAGE_SIZE and $MAX_PAGE_SIZE"
            }
            return SealiteConfig(compatibility, iterations, size)
        }
    }

    /**
     * SQLCipher major-version defaults (cipher, KDF, HMAC, page size). New versions may be added in
     * minor releases: keep an `else` branch when matching.
     *
     * [SQLCIPHER_4]: AES-256-CBC, PBKDF2-HMAC-SHA512 with 256000 iterations, HMAC-SHA512, 4096-byte
     * pages. [SQLCIPHER_3]: PBKDF2-HMAC-SHA1 with 64000 iterations, HMAC-SHA1, 1024-byte pages.
     */
    public enum class Compatibility(
        internal val pragmaValue: Int,
    ) {
        SQLCIPHER_3(3),
        SQLCIPHER_4(4),
    }

    public companion object {
        private const val MIN_PAGE_SIZE = 512
        private const val MAX_PAGE_SIZE = 65536

        /** SQLCipher 4 defaults. */
        @JvmField
        public val DEFAULT: SealiteConfig = Builder().build()

        /** A new [Builder] (for Java; Kotlin can use the `SealiteConfig { }` function). */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/** Builds a [SealiteConfig]: `SealiteConfig { compatibility = SealiteConfig.Compatibility.SQLCIPHER_3 }`. */
public fun SealiteConfig(block: SealiteConfig.Builder.() -> Unit): SealiteConfig =
    SealiteConfig.Builder().apply(block).build()
