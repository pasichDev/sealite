// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

/** Deletes [path] if it exists. Throws if it exists and can not be deleted. */
internal expect fun deleteFileIfExists(path: String)

/**
 * Renames [from] to [to] on the same file system, atomically: a reader sees either no [to] or the
 * complete file. Throws if [to] exists or the rename fails.
 */
internal expect fun moveFileAtomically(from: String, to: String)

/** The main database file and the sidecar files SQLite may create next to it. */
internal fun databaseFiles(path: String): List<String> = listOf(path, "$path-journal", "$path-wal", "$path-shm")
