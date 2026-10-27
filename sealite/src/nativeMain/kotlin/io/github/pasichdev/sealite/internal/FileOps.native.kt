// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.link
import platform.posix.strerror
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteFileIfExists(path: String) {
    if (unlink(path) != 0 && errno != ENOENT) {
        error("Can not delete $path: ${strerror(errno)?.toKString()}")
    }
}

/** `link` refuses an existing target (EEXIST), so the move never replaces a file. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun moveFileAtomically(from: String, to: String) {
    if (link(from, to) != 0) error("Can not move $from to $to: ${strerror(errno)?.toKString()}")
    if (unlink(from) != 0) error("Moved $from to $to but can not remove the source: ${strerror(errno)?.toKString()}")
}
