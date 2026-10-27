// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

/*
 * Platform helpers for the shared tests. Everything else in commonTest is platform neutral, so
 * file access goes through these.
 */

/** A path in a fresh temporary directory; no file exists there yet. */
internal expect fun newTempDatabasePath(): String

/** The whole file, or null if it does not exist. */
internal expect fun readFileBytes(path: String): ByteArray?

internal expect fun writeFileBytes(path: String, bytes: ByteArray)

/** Removes the database and its `-wal`, `-shm` and `-journal` companions. */
internal expect fun deleteDatabaseFiles(path: String)

internal val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()

internal fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    outer@ for (start in 0..size - needle.size) {
        for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
        return true
    }
    return false
}

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/** True where SQLCipher uses CommonCrypto instead of OpenSSL. */
internal expect val isApplePlatform: Boolean
