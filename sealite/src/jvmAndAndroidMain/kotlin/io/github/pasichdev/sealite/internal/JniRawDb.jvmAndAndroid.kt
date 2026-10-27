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
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import java.util.concurrent.locks.LockSupport

/**
 * A native `sqlite3*` handle driven through JNI.
 *
 * One lock per connection, shared by its statements ([lock]): every native call on the handle or on
 * one of its statements runs under it, so [close] can never free memory another thread is using.
 * [close] calls `sqlite3_interrupt` (the one call SQLite allows from any thread, so it runs without
 * the lock) until it holds the lock, to cut a running query short, then finalizes the statements
 * still open and closes the handle. Afterwards every call on the connection or on a statement of it
 * throws `SQLITE_MISUSE` instead of touching freed memory.
 */
internal class JniRawDb(
    private val connectionPointer: Long,
) : RawDb {
    internal val lock = reentrantLock()

    // Set by the first close() before the interrupt; the handle is freed only after that call
    // returned from nativeInterrupt, so interrupting never touches freed memory.
    private val closeRequested = atomic(false)

    // Both guarded by lock.
    private var closed = false
    private val statements = HashSet<JniStatement>()

    override fun key(key: ByteArray) = lock.withLock {
        throwIfClosed()
        nativeKey(connectionPointer, key)
    }

    override fun rekey(key: ByteArray) = lock.withLock {
        throwIfClosed()
        nativeRekey(connectionPointer, key)
    }

    override fun prepare(sql: String): SQLiteStatement = lock.withLock {
        throwIfClosed()
        JniStatement(this, nativePrepare(connectionPointer, sql)).also { statements.add(it) }
    }

    override fun inTransaction(): Boolean = lock.withLock {
        throwIfClosed()
        nativeInTransaction(connectionPointer)
    }

    override fun close() {
        // Compare-and-set: of any number of racing or repeated calls only one frees the handle.
        if (!closeRequested.compareAndSet(false, true)) return
        // Interrupt until the lock is free, not once: sqlite3_step clears a pending interrupt when it
        // starts with no other statement running, so one that lands in the instant between another
        // thread taking the lock and starting its step would be lost, and this wait as long as that
        // whole query.
        while (true) {
            nativeInterrupt(connectionPointer)
            if (lock.tryLock()) break
            LockSupport.parkNanos(RETRY_NANOS)
        }
        try {
            statements.toList().forEach { it.finalizeLocked() }
            statements.clear()
            closed = true
            nativeClose(connectionPointer)
        } finally {
            lock.unlock()
        }
    }

    /** Called by a statement, under [lock], once it has finalized itself. */
    internal fun forget(statement: JniStatement) {
        statements.remove(statement)
    }

    /** Must hold [lock]. */
    internal fun throwIfClosed() {
        if (closed) throwSQLiteException(OpenSequence.SQLITE_MISUSE, "connection is closed")
    }

    private companion object {
        private const val RETRY_NANOS = 1_000_000L
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

private external fun nativeInterrupt(pointer: Long)

private external fun nativeClose(pointer: Long)
