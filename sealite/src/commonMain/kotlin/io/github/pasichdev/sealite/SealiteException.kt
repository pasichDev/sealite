// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

/**
 * Sealite refused a database or could not finish a maintenance operation; [error] says why.
 *
 * ```
 * try {
 *     db.noteDao().all()
 * } catch (e: Exception) {
 *     when (Sealite.errorOf(e)) {
 *         SealiteError.WRONG_KEY -> askForTheKeyAgain()
 *         SealiteError.NOT_ENCRYPTED -> migrate()
 *         else -> throw e
 *     }
 * }
 * ```
 *
 * This is deliberately not an `androidx.sqlite.SQLiteException`: Room's corruption recovery
 * (`allowDataLossOnRecovery`) deletes the database only for `SQLiteException`, so a wrong key can
 * never make Room delete an encrypted database. Plain SQLite failures (I/O, constraints, a damaged
 * page under the right key) are still reported as `SQLiteException`.
 *
 * @property error why Sealite refused.
 */
public class SealiteException internal constructor(
    public val error: SealiteError,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("${error.name}: $message", cause)
