// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import java.io.File
import java.nio.file.Files

internal actual fun newTempDatabasePath(): String {
    val dir = Files.createTempDirectory("sealite-test").toFile()
    dir.deleteOnExit()
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

internal actual val isApplePlatform: Boolean
    get() = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("ios") }
