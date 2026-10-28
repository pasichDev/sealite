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
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_interrupt
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_open_v2
import io.github.pasichdev.sealite.cinterop.sealite_sqlite3_prepare16_v2
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.utf16
import kotlinx.cinterop.value
import platform.posix.usleep

/**
 * A native `sqlite3*` handle driven through cinterop.
 *
 * One lock per connection, shared by its statements ([lock]): every native call on the handle or on
 * one of its statements runs under it, so [close] can never free memory another thread is using.
 * [close] calls `sqlite3_interrupt` (the one call SQLite allows from any thread, so it runs without
 * the lock) until it holds the lock, to cut a running query short, then finalizes the statements
 * still open and closes the handle. Afterwards every call on the connection or on a statement of it
 * throws `SQLITE_MISUSE` instead of touching freed memory.
 */
internal class NativeRawDb(
    private val dbPointer: CPointer<sqlite3>,
) : RawDb {
    internal val lock = reentrantLock()

    // Set by the first close() before the interrupt; the handle is freed only after the interrupt
    // returned, so interrupting never touches freed memory.
    private val closeRequested = atomic(false)

    // Both guarded by lock.
    private var closed = false
    private val statements = HashSet<NativeStatement>()

    override fun key(key: ByteArray) = lock.withLock {
        throwIfClosed()
        keyDatabase(dbPointer, key)
    }

    override fun rekey(key: ByteArray) = lock.withLock {
        throwIfClosed()
        rekeyDatabase(dbPointer, key)
    }

    override fun prepare(sql: String): SQLiteStatement = lock.withLock {
        throwIfClosed()
        memScoped {
            val stmtPointer = allocPointerTo<sqlite3_stmt>()
            // Kotlin/Native uses UTF-16 character encoding by default.
            val resultCode =
                sealite_sqlite3_prepare16_v2(
                    db = dbPointer,
                    zSql = sql.utf16,
                    nByte = sql.length * 2,
                    ppStmt = stmtPointer.ptr,
                    pzTail = null,
                )
            if (resultCode != SQLITE_OK) {
                throwSQLiteException(resultCode, dbPointer.getErrorMsg())
            }
            // Empty, whitespace-only or comment-only SQL compiles to nothing: no statement object
            // around a NULL pointer.
            val statement = stmtPointer.value ?: throwSQLiteException(SQLITE_MISUSE, "no SQL statement")
            NativeStatement(this@NativeRawDb, dbPointer, statement).also { statements.add(it) }
        }
    }

    override fun inTransaction(): Boolean = lock.withLock {
        throwIfClosed()
        sealite_sqlite3_get_autocommit(dbPointer) == 0
    }

    override fun close() {
        // Compare-and-set: of any number of racing or repeated calls only one releases the handle.
        if (!closeRequested.compareAndSet(false, true)) return
        // Interrupt until the lock is free, not once: sqlite3_step clears a pending interrupt when it
        // starts with no other statement running, so one that lands in the instant between another
        // thread taking the lock and starting its step would be lost, and this wait as long as that
        // whole query.
        while (true) {
            sealite_sqlite3_interrupt(dbPointer)
            if (lock.tryLock()) break
            usleep(RETRY_MICROSECONDS)
        }
        try {
            statements.toList().forEach { it.finalizeLocked() }
            statements.clear()
            closed = true
            sealite_sqlite3_close_v2(dbPointer)
        } finally {
            lock.unlock()
        }
    }

    /** Called by a statement, under [lock], once it has finalized itself. */
    internal fun forget(statement: NativeStatement) {
        statements.remove(statement)
    }

    /** Must hold [lock]. */
    internal fun throwIfClosed() {
        if (closed) throwSQLiteException(SQLITE_MISUSE, "connection is closed")
    }

    private companion object {
        private const val RETRY_MICROSECONDS = 1000u
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
