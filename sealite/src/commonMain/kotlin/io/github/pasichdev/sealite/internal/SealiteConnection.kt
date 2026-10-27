// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.throwSQLiteException
import kotlin.concurrent.Volatile

/** A keyed, verified connection. Only [OpenSequence] creates one. */
internal class SealiteConnection(
    private val db: RawDb,
) : SQLiteConnection {
    @Volatile private var isClosed = false

    override fun inTransaction(): Boolean {
        throwIfClosed()
        return db.inTransaction()
    }

    override fun prepare(sql: String): SQLiteStatement {
        throwIfClosed()
        return db.prepare(sql)
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            db.close()
        }
    }

    private fun throwIfClosed() {
        if (isClosed) throwSQLiteException(OpenSequence.SQLITE_MISUSE, "connection is closed")
    }
}
