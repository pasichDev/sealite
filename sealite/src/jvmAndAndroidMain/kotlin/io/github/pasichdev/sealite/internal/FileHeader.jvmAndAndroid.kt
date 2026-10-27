// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import java.io.File

/**
 * Null only if [path] does not exist. A file that exists but can not be read (permissions, a
 * directory, an I/O error) throws `IOException`, so a caller never mistakes it for a missing file.
 */
internal actual fun readFileHeader(path: String, count: Int): ByteArray? {
    val file = File(path)
    if (!file.exists()) return null
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
