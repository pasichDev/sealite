// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import io.github.pasichdev.sealite.internal.OpenSequence

/**
 * An [SQLiteDriver] whose every connection is SQLCipher-encrypted with a [SealiteKey].
 *
 * ```
 * val driver = SealiteDriver(SealiteKey.raw(dek))
 * val db = Room.databaseBuilder<AppDatabase>(name = path).setDriver(driver).build()
 * // shutdown: db.close(), then driver.close()
 * ```
 *
 * The driver keeps its own copy of [key] (Room opens pool connections lazily) and wipes it in
 * [close]; the caller may close its own key right after construction. A connection is returned
 * only after the key was applied and the database verified: a wrong key, a plaintext database, or
 * a build that somehow linked a plain SQLite, fails [open] with an `SQLiteException`; pass it (or
 * the exception Room wraps it in) to [Sealite.errorOf] to get the [SealiteError].
 *
 * Never combine with Room's `allowDataLossOnRecovery(true)`: a wrong key looks exactly like a
 * corrupt file, and Room would delete the database.
 *
 * @param key the key every connection is opened with; copied, so the caller may close its own.
 * @param config how the file is read and written; the SQLCipher 4 defaults unless the file says
 *   otherwise.
 */
public class SealiteDriver(
    key: SealiteKey,
    private val config: SealiteConfig,
) : SQLiteDriver,
    AutoCloseable {
    /** A driver with [SealiteConfig.DEFAULT] (SQLCipher 4). */
    public constructor(key: SealiteKey) : this(key, SealiteConfig.DEFAULT)

    private val key: SealiteKey = key.copy()

    /** Room keeps its own connection pool, as it does for the bundled driver. */
    @Suppress("INAPPLICABLE_JVM_NAME")
    @get:kotlin.jvm.JvmName("hasConnectionPool")
    override val hasConnectionPool: Boolean get() = false

    override fun open(fileName: String): SQLiteConnection = open(fileName, OPEN_READWRITE or OPEN_CREATE)

    /**
     * Opens with explicit `SQLITE_OPEN_*` [flags].
     *
     * @throws androidx.sqlite.SQLiteException if the key does not open the file
     *   ([SealiteError.WRONG_KEY]), the file is not encrypted ([SealiteError.NOT_ENCRYPTED]) or it
     *   can not be opened.
     * @throws IllegalStateException if the driver is closed.
     */
    public fun open(fileName: String, flags: Int): SQLiteConnection {
        check(!key.isClosed) { "SealiteDriver is closed" }
        return OpenSequence.open(fileName, flags, key, config)
    }

    /** Wipes the driver's key. Close every connection (the `RoomDatabase`) first. */
    override fun close() {
        key.close()
    }

    private companion object {
        const val OPEN_READWRITE = 0x00000002
        const val OPEN_CREATE = 0x00000004
    }
}
