/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modified for Sealite: ported from NativeSQLiteConnection.kt and the open path of
 * NativeSQLiteDriver.kt. The connection is a RawDb, gains key / rekey, and extension loading is
 * dropped. Keys live in NativeKey.kt.
 */

package io.github.pasichdev.sealite.internal

import androidx.sqlite.SQLiteStatement
import androidx.sqlite.throwSQLiteException
import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import io.github.pasichdev.sealite.cinterop.SQLITE_MISUSE
import io.github.pasichdev.sealite.cinterop.SQLITE_OK
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_close_v2
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_extended_result_codes
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_get_autocommit
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_open_v2
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_prepare16_v2
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.utf16
import kotlinx.cinterop.value

/** A native `sqlite3*` handle driven through cinterop. */
internal class NativeRawDb(
    private val dbPointer: CPointer<sqlite3>,
) : RawDb {
    private val closed = atomic(false)

    override fun key(key: ByteArray) {
        throwIfClosed()
        keyDatabase(dbPointer, key)
    }

    override fun rekey(key: ByteArray) {
        throwIfClosed()
        rekeyDatabase(dbPointer, key)
    }

    override fun prepare(sql: String): SQLiteStatement = memScoped {
        throwIfClosed()
        val stmtPointer = allocPointerTo<sqlite3_stmt>()
        // Kotlin/Native uses UTF-16 character encoding by default.
        val sqlUtf16 = sql.utf16
        val resultCode =
            sealite_sqlite3_prepare16_v2(
                db = dbPointer,
                zSql = sqlUtf16,
                nByte = sqlUtf16.size,
                ppStmt = stmtPointer.ptr,
                pzTail = null,
            )
        if (resultCode != SQLITE_OK) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
        NativeStatement(dbPointer, stmtPointer.value!!)
    }

    override fun inTransaction(): Boolean {
        throwIfClosed()
        return sealite_sqlite3_get_autocommit(dbPointer) == 0
    }

    override fun close() {
        // Compare-and-set: of any number of racing or repeated calls only one releases the handle.
        if (closed.compareAndSet(false, true)) sealite_sqlite3_close_v2(dbPointer)
    }

    private fun throwIfClosed() {
        if (closed.value) {
            throwSQLiteException(SQLITE_MISUSE, "connection is closed")
        }
    }
}

internal actual fun rawOpen(fileName: String, flags: Int): RawDb = memScoped {
    val dbPointer = allocPointerTo<sqlite3>()
    val openResult = sealite_sqlite3_open_v2(filename = fileName, ppDb = dbPointer.ptr, flags = flags, zVfs = null)
    val db = dbPointer.value
    if (openResult != SQLITE_OK) {
        // sqlite3_open_v2 returns a handle (carrying the error) even on most failures.
        val message = db?.getErrorMsg()
        db?.let { sealite_sqlite3_close_v2(it) }
        throwSQLiteException(openResult, message)
    }
    // Extended result codes, as the JNI binding does.
    val extendedResult = sealite_sqlite3_extended_result_codes(db!!, 1)
    if (extendedResult != SQLITE_OK) {
        sealite_sqlite3_close_v2(db)
        throwSQLiteException(extendedResult, null)
    }
    NativeRawDb(db)
}
