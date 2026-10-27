// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import androidx.sqlite.SQLiteStatement

/**
 * The platform binding to one native `sqlite3*` handle: JNI on JVM/Android, cinterop on Apple.
 * Deliberately dumb. Every decision about keys, pragmas and verification is made by
 * [OpenSequence], once, for all platforms.
 */
internal interface RawDb {
    /**
     * `sqlite3_key_v2(db, "main", key)`. The binding copies [key] into native memory, wipes that
     * copy after the call and never turns it into SQL text; [key] stays the caller's to wipe.
     * Throws `SQLiteException` on a non-OK result.
     */
    fun key(key: ByteArray)

    /** `sqlite3_rekey_v2(db, "main", key)`, same rules as [key]. */
    fun rekey(key: ByteArray)

    fun prepare(sql: String): SQLiteStatement

    fun inTransaction(): Boolean

    /** `sqlite3_close_v2`. Idempotent. */
    fun close()
}

/** Opens the native handle (`sqlite3_open_v2`), without a key. Closes it again on failure. */
internal expect fun rawOpen(fileName: String, flags: Int): RawDb

/** Fills [bytes] with zeros. Best effort: the runtime may have copied the array before. */
internal fun ByteArray.wipe() = fill(0)

/** Reads at most [count] bytes from the start of [path]; null if it does not exist. */
internal expect fun readFileHeader(path: String, count: Int): ByteArray?
