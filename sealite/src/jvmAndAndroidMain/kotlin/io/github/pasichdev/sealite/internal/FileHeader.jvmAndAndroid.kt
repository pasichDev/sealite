// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import java.io.File

internal actual fun readFileHeader(path: String, count: Int): ByteArray? {
    val file = File(path)
    if (!file.isFile) return null
    file.inputStream().use { input ->
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }
}
