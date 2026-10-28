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
 * [to]. Throws [IllegalStateException] if [to] exists. JVM and Apple: a hard link, which fails
 * atomically when [to] exists, with no fallback (a file system without hard links fails instead).
 * Android, where apps may not create hard links: an existence check and a rename under a lock that
 * every Sealite publish of [to] takes, across threads and processes.
 */
internal expect fun publishWithoutReplacing(from: String, to: String)

/** The main database file and the sidecar files SQLite may create next to it. */
internal fun databaseFiles(path: String): List<String> = listOf(path, "$path-journal", "$path-wal", "$path-shm")
