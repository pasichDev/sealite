// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

/*
 * Key handling for the native binding: the only Kotlin code that hands key material to SQLCipher.
 *
 * The array is pinned and its address is passed straight to sqlite3_key_v2 / sqlite3_rekey_v2.
 * No copy goes into native memory and the key never becomes a String, a ByteArray copy or a
 * C string. Wiping the array afterwards is the caller's job (OpenSequence.applyKey does it).
 */

package io.github.pasichdev.sealite.internal

import androidx.sqlite.throwSQLiteException
import cnames.structs.sqlite3
import io.github.pasichdev.sealite.cinterop.SQLITE_MISUSE
import io.github.pasichdev.sealite.cinterop.SQLITE_OK
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_key_v2
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_rekey_v2
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

internal fun keyDatabase(db: CPointer<sqlite3>, key: ByteArray) {
    if (key.isEmpty()) throwSQLiteException(SQLITE_MISUSE, "empty key")
    val rc = key.usePinned { sealite_sqlite3_key_v2(db, "main", it.addressOf(0), key.size) }
    if (rc != SQLITE_OK) throwSQLiteException(rc, db.getErrorMsg())
}

internal fun rekeyDatabase(db: CPointer<sqlite3>, key: ByteArray) {
    if (key.isEmpty()) throwSQLiteException(SQLITE_MISUSE, "empty key")
    val rc = key.usePinned { sealite_sqlite3_rekey_v2(db, "main", it.addressOf(0), key.size) }
    if (rc != SQLITE_OK) throwSQLiteException(rc, db.getErrorMsg())
}
