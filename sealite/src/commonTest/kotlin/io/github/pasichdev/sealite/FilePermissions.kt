// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

/**
 * Removes every read permission from [path]. Returns false if that did not make the file
 * unreadable for this process (no permission bits on the platform, or running as the superuser):
 * the caller skips its check then.
 */
internal expect fun makeUnreadable(path: String): Boolean

/** The names (not paths) of the entries of [directory]. */
internal expect fun listDirectory(directory: String): List<String>
