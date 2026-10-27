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
 * Modified for Sealite: ported from BundledSQLiteConnection.jvmAndAndroid.kt. The connection is a
 * RawDb, gains key / rekey, and extension loading is dropped.
 */
@file:JvmName("JniRawDbKt")

package io.github.pasichdev.sealite.internal

import androidx.sqlite.SQLiteStatement
import androidx.sqlite.throwSQLiteException
import kotlinx.atomicfu.atomic

/** A native `sqlite3*` handle driven through JNI. */
internal class JniRawDb(
    private val connectionPointer: Long,
) : RawDb {
    private val closed = atomic(false)

    override fun key(key: ByteArray) {
        throwIfClosed()
        nativeKey(connectionPointer, key)
    }

    override fun rekey(key: ByteArray) {
        throwIfClosed()
        nativeRekey(connectionPointer, key)
    }

    override fun prepare(sql: String): SQLiteStatement {
        throwIfClosed()
        val statementPointer = nativePrepare(connectionPointer, sql)
        return JniStatement(connectionPointer, statementPointer)
    }

    override fun inTransaction(): Boolean {
        throwIfClosed()
        return nativeInTransaction(connectionPointer)
    }

    override fun close() {
        // Compare-and-set: of any number of racing or repeated calls only one frees the handle.
        if (closed.compareAndSet(false, true)) nativeClose(connectionPointer)
    }

    private fun throwIfClosed() {
        if (closed.value) {
            throwSQLiteException(OpenSequence.SQLITE_MISUSE, "connection is closed")
        }
    }
}

internal actual fun rawOpen(fileName: String, flags: Int): RawDb {
    NativeLibraryLoader.load()
    // UTF-8 with a trailing NUL, as sqlite3_open_v2 wants it (see sealite_jni.c).
    return JniRawDb(nativeOpen(fileName.encodeToByteArray() + 0, flags))
}

/** Loads the packaged native library once; each platform supplies its own search. */
internal expect object NativeLibraryLoader {
    fun load()
}

// Registered by JNI_OnLoad (sealite_jni.c) against this file's class, JniRawDbKt.
private external fun nativeOpen(fileName: ByteArray, flags: Int): Long

private external fun nativeKey(pointer: Long, key: ByteArray)

private external fun nativeRekey(pointer: Long, key: ByteArray)

private external fun nativeInTransaction(pointer: Long): Boolean

private external fun nativePrepare(pointer: Long, sql: String): Long

private external fun nativeClose(pointer: Long)
