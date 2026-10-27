// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

/** Deletes [path] if it exists. Throws if it exists and can not be deleted. */
internal expect fun deleteFileIfExists(path: String)

/**
 * Creates a new, empty file with an unpredictable name in the directory of [path]
 * (`.<name of path>.sealite-<random>`), exclusively: it never opens an existing file. Returns
 * its path.
 */
internal expect fun createUniqueFileNextTo(path: String): String

/**
 * Makes the complete file [from] appear under [to] and removes [from], never replacing an existing
 * [to]: a hard link, which fails atomically when [to] exists, then an unlink of [from]. Throws
 * [IllegalStateException] if [to] exists.
 */
internal expect fun publishWithoutReplacing(from: String, to: String)

/** The main database file and the sidecar files SQLite may create next to it. */
internal fun databaseFiles(path: String): List<String> = listOf(path, "$path-journal", "$path-wal", "$path-shm")
