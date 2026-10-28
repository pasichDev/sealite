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
 * Modified for Sealite: ported from BundledSQLiteStatement.jvmAndAndroid.kt, renamed, and the
 * class is internal instead of an actual of a public expect class.
 */
@file:JvmName("JniStatementKt")

package io.github.pasichdev.sealite.internal

import androidx.sqlite.SQLiteStatement
import androidx.sqlite.throwSQLiteException
import kotlinx.atomicfu.locks.withLock

/**
 * A prepared statement of a [JniRawDb]. Every call runs under the connection's lock (see [JniRawDb]),
 * and the connection finalizes the statements still open when it closes.
 *
 * `getText` on a SQL NULL returns the empty string on every platform, like the bundled driver of
 * androidx.sqlite on the JVM; use `isNull` to tell NULL from `""`. A lone UTF-16 surrogate in
 * `bindText` is stored as U+FFFD.
 *
 * [close] waits for the connection's lock: closing a statement while another thread is inside a call
 * on the same connection (a long `step`, say) blocks until that call returns. Only closing the
 * connection interrupts a running query.
 */
internal class JniStatement(
    private val owner: JniRawDb,
    private val statementPointer: Long,
) : SQLiteStatement {
    // Guarded by owner.lock.
    private var closed = false

    override fun bindBlob(index: Int, value: ByteArray) = locked { nativeBindBlob(statementPointer, index, value) }

    override fun bindDouble(index: Int, value: Double) = locked { nativeBindDouble(statementPointer, index, value) }

    override fun bindLong(index: Int, value: Long) = locked { nativeBindLong(statementPointer, index, value) }

    override fun bindText(index: Int, value: String) = locked { nativeBindText(statementPointer, index, value) }

    override fun bindNull(index: Int) = locked { nativeBindNull(statementPointer, index) }

    override fun getBlob(index: Int): ByteArray = locked { nativeGetBlob(statementPointer, index) }

    override fun getDouble(index: Int): Double = locked { nativeGetDouble(statementPointer, index) }

    override fun getLong(index: Int): Long = locked { nativeGetLong(statementPointer, index) }

    override fun getText(index: Int): String = locked { nativeGetText(statementPointer, index) }

    override fun isNull(index: Int): Boolean = locked {
        nativeGetColumnType(statementPointer, index) == COLUMN_TYPE_NULL
    }

    override fun getColumnCount(): Int = locked { nativeGetColumnCount(statementPointer) }

    override fun getColumnName(index: Int): String = locked { nativeGetColumnName(statementPointer, index) }

    override fun getColumnType(index: Int): Int = locked { nativeGetColumnType(statementPointer, index) }

    override fun step(): Boolean = locked { nativeStep(statementPointer) }

    override fun reset() = locked { nativeReset(statementPointer) }

    override fun clearBindings() = locked { nativeClearBindings(statementPointer) }

    override fun close() = owner.lock.withLock {
        if (!closed) {
            owner.forget(this)
            finalizeLocked()
        }
    }

    /** Finalizes a statement that is still open. Must hold the connection's lock. */
    internal fun finalizeLocked() {
        closed = true
        nativeClose(statementPointer)
    }

    private inline fun <T> locked(block: () -> T): T = owner.lock.withLock {
        owner.throwIfClosed()
        if (closed) throwSQLiteException(OpenSequence.SQLITE_MISUSE, "statement is closed")
        block()
    }

    private companion object {
        private const val COLUMN_TYPE_NULL = 5
    }
}

private external fun nativeBindBlob(pointer: Long, index: Int, value: ByteArray)

private external fun nativeBindDouble(pointer: Long, index: Int, value: Double)

private external fun nativeBindLong(pointer: Long, index: Int, value: Long)

private external fun nativeBindText(pointer: Long, index: Int, value: String)

private external fun nativeBindNull(pointer: Long, index: Int)

private external fun nativeStep(pointer: Long): Boolean

private external fun nativeGetBlob(pointer: Long, index: Int): ByteArray

private external fun nativeGetDouble(pointer: Long, index: Int): Double

private external fun nativeGetLong(pointer: Long, index: Int): Long

private external fun nativeGetText(pointer: Long, index: Int): String

private external fun nativeGetColumnCount(pointer: Long): Int

private external fun nativeGetColumnName(pointer: Long, index: Int): String

private external fun nativeGetColumnType(pointer: Long, index: Int): Int

private external fun nativeReset(pointer: Long)

private external fun nativeClearBindings(pointer: Long)

private external fun nativeClose(pointer: Long)
