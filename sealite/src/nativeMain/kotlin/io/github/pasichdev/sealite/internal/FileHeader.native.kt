// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fopen
import platform.posix.fread
import platform.posix.stat
import platform.posix.strerror

/**
 * Null only if [path] does not exist. A file that exists but can not be read (permissions, not a
 * regular file, an I/O error) throws `IllegalStateException` with the errno text, so a caller never
 * mistakes it for a missing file; it is the counterpart of the `IOException` of the JVM binding.
 */
internal actual fun readFileHeader(path: String, count: Int): ByteArray? {
    val mode =
        memScoped {
            val info = alloc<stat>()
            if (stat(path, info.ptr) != 0) {
                val code = errno
                if (code == ENOENT || code == ENOTDIR) return null
                failRead(path, code)
            }
            info.st_mode.toInt() and S_IFMT
        }
    check(mode == S_IFREG) { "Can not read $path: not a regular file" }
    val file = fopen(path, "rb")
    if (file == null) failRead(path, errno)
    try {
        val buffer = ByteArray(count)
        val read =
            if (count == 0) {
                0
            } else {
                buffer.usePinned { fread(it.addressOf(0), 1.convert(), count.convert(), file).toInt() }
            }
        if (read < count && ferror(file) != 0) failRead(path, errno)
        return buffer.copyOf(read)
    } finally {
        fclose(file)
    }
}

private fun failRead(path: String, code: Int): Nothing =
    throw IllegalStateException("Can not read $path: ${strerror(code)?.toKString() ?: "error $code"}")
