// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import java.io.File
import java.util.UUID

// java.io only: java.nio.file.Files needs API 26 and the library supports API 24.
internal actual fun newTempDatabasePath(): String {
    val dir = File(System.getProperty("java.io.tmpdir"), "sealite-test-${UUID.randomUUID()}")
    check(dir.mkdirs()) { "Cannot create $dir" }
    return File(dir, "test.db").path
}

internal actual fun readFileBytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

internal actual fun writeFileBytes(path: String, bytes: ByteArray) {
    File(path).writeBytes(bytes)
}

internal actual fun deleteDatabaseFiles(path: String) {
    for (suffix in listOf("", "-wal", "-shm", "-journal")) File(path + suffix).delete()
    File(path).parentFile?.delete()
}

internal actual val isApplePlatform: Boolean = false
