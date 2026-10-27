// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

/**
 * Why Sealite refused a database. Read it from any exception with [Sealite.errorOf]; Room wraps
 * and rethrows driver errors, so do not parse messages yourself.
 *
 * New values may be added in minor releases: keep an `else` branch when matching.
 */
public enum class SealiteError(
    internal val token: String,
) {
    /**
     * The key (or the [SealiteConfig]) does not open the file. An encrypted database is
     * indistinguishable from random bytes, so a damaged file also reports this: never treat it as
     * corruption to recover from by deleting the database.
     */
    WRONG_KEY("SEALITE_WRONG_KEY"),

    /** The file is a plain, unencrypted SQLite database; see [Sealite.encryptPlaintext]. */
    NOT_ENCRYPTED("SEALITE_NOT_ENCRYPTED"),

    /** SQLCipher reports the connection as not keyed (`PRAGMA cipher_status` is not 1). */
    NOT_KEYED("SEALITE_NOT_KEYED"),

    /** The linked SQLite has no encryption: a build or packaging error, never a user error. */
    PROVIDER_MISSING("SEALITE_PROVIDER_MISSING"),

    /**
     * [Sealite.rekey] failed before the new key was committed: the file still opens with the old
     * key (SQLite's journal rolls the partial write back).
     */
    REKEY_FAILED("SEALITE_REKEY_FAILED"),

    /**
     * [Sealite.rekey] committed the new key but could not finish or verify it (checkpoint, reopen
     * or `quick_check` failed). The file most likely opens with the new key: keep both keys and
     * find out with [Sealite.check].
     */
    REKEY_UNVERIFIED("SEALITE_REKEY_UNVERIFIED"),
}
