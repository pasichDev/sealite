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
 * Modified for Sealite: ported from NativeSQLiteStatement.kt, renamed, internal instead of a
 * public RestrictTo class, and bound to the renamed sealite_sqlite3_* functions.
 */

package io.github.pasichdev.sealite.internal

import androidx.sqlite.SQLiteStatement
import androidx.sqlite.throwSQLiteException
import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import io.github.pasichdev.sealite.cinterop.SQLITE_DONE
import io.github.pasichdev.sealite.cinterop.SQLITE_MISUSE
import io.github.pasichdev.sealite.cinterop.SQLITE_NOMEM
import io.github.pasichdev.sealite.cinterop.SQLITE_NULL
import io.github.pasichdev.sealite.cinterop.SQLITE_OK
import io.github.pasichdev.sealite.cinterop.SQLITE_RANGE
import io.github.pasichdev.sealite.cinterop.SQLITE_ROW
import io.github.pasichdev.sealite.cinterop.SQLITE_TRANSIENT
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_bind_blob
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_bind_double
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_bind_int64
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_bind_null
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_bind_text16
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_bind_zeroblob
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_clear_bindings
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_blob
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_bytes
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_bytes16
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_count
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_double
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_int64
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_name16
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_text16
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_column_type
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_errcode
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_finalize
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_reset
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_step
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_stmt_busy
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.utf16

/**
 * A prepared statement of a [NativeRawDb]. Every call runs under the connection's lock (see
 * [NativeRawDb]), and the connection finalizes the statements still open when it closes.
 *
 * `getText` on a SQL NULL returns the empty string on every platform, like the bundled driver of
 * androidx.sqlite on the JVM; use `isNull` to tell NULL from `""`. A lone UTF-16 surrogate in
 * `bindText` is stored as U+FFFD.
 *
 * [close] waits for the connection's lock: closing a statement while another thread is inside a call
 * on the same connection (a long `step`, say) blocks until that call returns. Only closing the
 * connection interrupts a running query.
 */
internal class NativeStatement(
    private val owner: NativeRawDb,
    private val dbPointer: CPointer<sqlite3>,
    private val stmtPointer: CPointer<sqlite3_stmt>,
) : SQLiteStatement {
    // Guarded by owner.lock.
    private var closed = false

    override fun bindBlob(index: Int, value: ByteArray) = locked {
        val resultCode =
            if (value.isEmpty()) {
                // An empty BLOB, not NULL, whatever pointer an empty array would give.
                sealite_sqlite3_bind_zeroblob(stmtPointer, index, 0)
            } else {
                sealite_sqlite3_bind_blob(stmtPointer, index, value.toCValues(), value.size, SQLITE_TRANSIENT)
            }
        if (resultCode != SQLITE_OK) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun bindLong(index: Int, value: Long) = locked {
        val resultCode = sealite_sqlite3_bind_int64(stmtPointer, index, value)
        if (resultCode != SQLITE_OK) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun bindDouble(index: Int, value: Double) = locked {
        val resultCode = sealite_sqlite3_bind_double(stmtPointer, index, value)
        if (resultCode != SQLITE_OK) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun bindText(index: Int, value: String) = locked {
        // SQLite would read a lone surrogate as the start of a pair and swallow the next unit.
        val text = value.replacingLoneSurrogates()
        // The byte length is passed explicitly (2 per unit), so embedded U+0000 is kept.
        val resultCode =
            sealite_sqlite3_bind_text16(stmtPointer, index, text.utf16, text.length * 2, SQLITE_TRANSIENT)
        if (resultCode != SQLITE_OK) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun bindNull(index: Int) = locked {
        val resultCode = sealite_sqlite3_bind_null(stmtPointer, index)
        if (resultCode != SQLITE_OK) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun getText(index: Int): String = locked {
        throwIfNoRow()
        throwIfInvalidColumn(index)
        // Exactly sqlite3_column_bytes16() / 2 units, read after sqlite3_column_text16() as SQLite
        // requires: a NUL scan (toKStringFromUtf16) would cut a value with an embedded U+0000 short.
        val value = sealite_sqlite3_column_text16(stmtPointer, index)
        if (value == null) {
            throwIfOutOfMemory()
            return@locked "" // SQL NULL, like the bundled driver on the JVM
        }
        val units = sealite_sqlite3_column_bytes16(stmtPointer, index) / 2
        val text = value.reinterpret<UShortVar>()
        CharArray(units) { text[it].toInt().toChar() }.concatToString()
    }

    override fun getLong(index: Int): Long = locked {
        throwIfNoRow()
        throwIfInvalidColumn(index)
        sealite_sqlite3_column_int64(stmtPointer, index)
    }

    override fun getBlob(index: Int): ByteArray = locked {
        throwIfNoRow()
        throwIfInvalidColumn(index)
        val blob = sealite_sqlite3_column_blob(stmtPointer, index)
        if (blob == null) throwIfOutOfMemory()
        val size = sealite_sqlite3_column_bytes(stmtPointer, index)
        if (size == 0) throwIfOutOfMemory()
        if (blob != null && size > 0) {
            blob.readBytes(size)
        } else {
            ByteArray(0)
        }
    }

    override fun getDouble(index: Int): Double = locked {
        throwIfNoRow()
        throwIfInvalidColumn(index)
        sealite_sqlite3_column_double(stmtPointer, index)
    }

    override fun isNull(index: Int): Boolean = locked { columnType(index) == SQLITE_NULL }

    override fun getColumnType(index: Int): Int = locked { columnType(index) }

    override fun getColumnCount(): Int = locked { sealite_sqlite3_column_count(stmtPointer) }

    override fun getColumnName(index: Int): String = locked {
        throwIfInvalidColumn(index)
        val namePointer = sealite_sqlite3_column_name16(stmtPointer, index) ?: throw OutOfMemoryError()
        namePointer.reinterpret<UShortVar>().toKStringFromUtf16()
    }

    override fun step(): Boolean = locked {
        val resultCode = sealite_sqlite3_step(stmtPointer)
        when (resultCode) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun reset() = locked {
        val resultCode = sealite_sqlite3_reset(stmtPointer)
        if (resultCode != SQLITE_OK) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun clearBindings() = locked {
        val resultCode = sealite_sqlite3_clear_bindings(stmtPointer)
        if (resultCode != SQLITE_OK) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun close() = owner.lock.withLock {
        if (!closed) {
            owner.forget(this)
            finalizeLocked()
        }
    }

    /** Finalizes a statement that is still open. Must hold the connection's lock. */
    internal fun finalizeLocked() {
        closed = true
        sealite_sqlite3_finalize(stmtPointer)
    }

    private inline fun <T> locked(block: () -> T): T = owner.lock.withLock {
        owner.throwIfClosed()
        if (closed) throwSQLiteException(SQLITE_MISUSE, "statement is closed")
        block()
    }

    private fun columnType(index: Int): Int {
        throwIfNoRow()
        throwIfInvalidColumn(index)
        return sealite_sqlite3_column_type(stmtPointer, index)
    }

    private fun throwIfNoRow() {
        if (sealite_sqlite3_stmt_busy(stmtPointer) == 0) {
            throwSQLiteException(SQLITE_MISUSE, "no row")
        }
    }

    private fun throwIfInvalidColumn(index: Int) {
        if (index < 0 || index >= sealite_sqlite3_column_count(stmtPointer)) {
            throwSQLiteException(SQLITE_RANGE, "column index out of range")
        }
    }

    private fun throwIfOutOfMemory() {
        val lastResultCode = sealite_sqlite3_errcode(dbPointer)
        if (lastResultCode == SQLITE_NOMEM) {
            throw OutOfMemoryError()
        }
    }
}

/** [this] with every unpaired UTF-16 surrogate replaced by U+FFFD; the same instance if there is none. */
private fun String.replacingLoneSurrogates(): String {
    var fixed: CharArray? = null
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            i += 2
            continue
        }
        if (c.isSurrogate()) {
            val chars = fixed ?: toCharArray().also { fixed = it }
            chars[i] = '\uFFFD'
        }
        i++
    }
    return fixed?.concatToString() ?: this
}
