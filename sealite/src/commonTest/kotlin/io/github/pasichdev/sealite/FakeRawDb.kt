// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteStatement
import io.github.pasichdev.sealite.internal.RawDb

/** What a [FakeRawDb] answers to one statement. */
internal sealed interface Answer {
    /** One row with a single text column; [text] null is SQL NULL. */
    data class Row(
        val text: String?,
    ) : Answer

    /** No row at all. */
    data object NoRow : Answer

    /** Preparing the statement fails with [error]. */
    data class Fails(
        val error: Throwable,
    ) : Answer
}

/**
 * A scripted [RawDb] for the decisions [io.github.pasichdev.sealite.internal.OpenSequence] makes
 * on what the native library answers, which a real SQLCipher never gets wrong on demand.
 */
internal class FakeRawDb(
    private val answers: Map<String, Answer>,
) : RawDb {
    var keyCalls = 0
        private set

    override fun key(key: ByteArray) {
        keyCalls++
    }

    override fun rekey(key: ByteArray) = error("not used")

    override fun prepare(sql: String): SQLiteStatement = when (val answer = answers.getValue(sql)) {
        is Answer.Row -> FakeStatement(answer.text, hasRow = true)
        Answer.NoRow -> FakeStatement(null, hasRow = false)
        is Answer.Fails -> throw answer.error
    }

    override fun inTransaction(): Boolean = false

    override fun close() = Unit
}

internal class FakeStatement(
    private val text: String?,
    private val hasRow: Boolean,
) : SQLiteStatement {
    private var stepped = false

    override fun step(): Boolean {
        val row = hasRow && !stepped
        stepped = true
        return row
    }

    override fun isNull(index: Int): Boolean = text == null

    override fun getText(index: Int): String = text ?: error("NULL")

    override fun close() = Unit

    override fun bindBlob(index: Int, value: ByteArray) = error("not used")

    override fun bindDouble(index: Int, value: Double) = error("not used")

    override fun bindLong(index: Int, value: Long) = error("not used")

    override fun bindText(index: Int, value: String) = error("not used")

    override fun bindNull(index: Int) = error("not used")

    override fun getBlob(index: Int): ByteArray = error("not used")

    override fun getDouble(index: Int): Double = error("not used")

    override fun getLong(index: Int): Long = error("not used")

    override fun getColumnCount(): Int = error("not used")

    override fun getColumnName(index: Int): String = error("not used")

    override fun getColumnType(index: Int): Int = error("not used")

    override fun reset() = error("not used")

    override fun clearBindings() = error("not used")
}
