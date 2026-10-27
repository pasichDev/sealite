// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal fun rawKey(seed: Int): SealiteKey = SealiteKey.raw(ByteArray(32) { (it + seed).toByte() })

internal fun SQLiteConnection.scalar(sql: String): String? = prepare(sql).use {
    if (it.step() && !it.isNull(0)) it.getText(0) else null
}

internal fun SQLiteConnection.column(sql: String): List<String> = prepare(sql).use {
    buildList { while (it.step()) add(it.getText(0)) }
}

/** Creates `note` and fills it. */
internal fun SQLiteConnection.createNotes(vararg bodies: String) {
    execSQL("CREATE TABLE note (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
    for (body in bodies) {
        prepare("INSERT INTO note (body) VALUES (?)").use {
            it.bindText(1, body)
            it.step()
        }
    }
}

internal fun SQLiteConnection.notes(): List<String> = column("SELECT body FROM note ORDER BY id")

/** Opens [path] with [key], runs [block], closes everything. */
internal fun <T> withDb(
    path: String,
    key: SealiteKey,
    config: SealiteConfig = SealiteConfig.DEFAULT,
    block: (SQLiteConnection) -> T,
): T = SealiteDriver(key, config).use { driver -> driver.open(path).use(block) }

/** The [SealiteError] that opening [path] with [key] fails with, or null if it opens. */
internal fun openError(path: String, key: SealiteKey): SealiteError? = try {
    withDb(path, key) {}
    null
} catch (e: androidx.sqlite.SQLiteException) {
    Sealite.errorOf(e)
}
