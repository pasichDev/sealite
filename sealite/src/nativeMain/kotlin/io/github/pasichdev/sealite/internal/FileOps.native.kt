// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.close
import platform.posix.errno
import platform.posix.link
import platform.posix.mkstemp
import platform.posix.strerror
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteFileIfExists(path: String) {
    if (unlink(path) != 0 && errno != ENOENT) {
        error("Can not delete $path: ${strerror(errno)?.toKString()}")
    }
}

/** `mkstemp` creates exclusively (O_EXCL), with a random name and mode 0600. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun createUniqueFileNextTo(path: String): String {
    val slash = path.lastIndexOf('/')
    val directory = if (slash >= 0) path.substring(0, slash + 1) else ""
    val name = path.substring(slash + 1)
    return memScoped {
        val template = "$directory.$name.sealite-XXXXXX".cstr.getPointer(this)
        val fd = mkstemp(template)
        if (fd < 0) error("Can not create a temporary file next to $path: ${strerror(errno)?.toKString()}")
        close(fd)
        template.toKString()
    }
}

/** `link` refuses an existing target (EEXIST) atomically, so the publish never replaces a file. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun publishWithoutReplacing(from: String, to: String) {
    if (link(from, to) != 0) {
        val code = errno
        if (code == EEXIST) error("$to already exists")
        error("Can not publish $from as $to: ${strerror(code)?.toKString()}")
    }
    if (unlink(from) != 0) error("Published $to but can not remove $from: ${strerror(errno)?.toKString()}")
}
