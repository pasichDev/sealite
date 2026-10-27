// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

internal actual fun newTempDatabasePath(): String {
    val dir = NSTemporaryDirectory().trimEnd('/') + "/sealite-test-" + NSUUID().UUIDString
    check(
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        ),
    ) {
        "can not create $dir"
    }
    return "$dir/test.db"
}

internal actual fun readFileBytes(path: String): ByteArray? =
    NSFileManager.defaultManager.contentsAtPath(path)?.let { data ->
        ByteArray(data.length.toInt()).also { bytes ->
            if (bytes.isNotEmpty()) bytes.usePinned { platform.posix.memcpy(it.addressOf(0), data.bytes, data.length) }
        }
    }

internal actual fun writeFileBytes(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("can not open $path for writing")
    try {
        if (bytes.isNotEmpty()) {
            val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
            check(written.toLong() == bytes.size.toLong()) { "short write to $path" }
        }
    } finally {
        fclose(file)
    }
}

internal actual fun deleteDatabaseFiles(path: String) {
    val files = NSFileManager.defaultManager
    for (suffix in listOf("", "-wal", "-shm", "-journal")) files.removeItemAtPath(path + suffix, error = null)
    files.removeItemAtPath(path.substringBeforeLast('/'), error = null)
}

internal actual val isApplePlatform: Boolean = true
