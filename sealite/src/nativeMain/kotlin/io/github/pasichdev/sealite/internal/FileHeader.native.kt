// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.stat

internal actual fun readFileHeader(path: String, count: Int): ByteArray? {
    val isRegularFile =
        memScoped {
            val info = alloc<stat>()
            stat(path, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFREG
        }
    if (!isRegularFile) return null
    val file = fopen(path, "rb") ?: return null
    try {
        val buffer = ByteArray(count)
        val read =
            if (count ==
                0
            ) {
                0
            } else {
                buffer.usePinned { fread(it.addressOf(0), 1.convert(), count.convert(), file).toInt() }
            }
        return buffer.copyOf(read)
    } finally {
        fclose(file)
    }
}
