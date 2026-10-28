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
    for (suffix in listOf("", "-wal", "-shm", "-journal")) deleteWithRetry(File(path + suffix))
    File(path).parentFile?.delete()
}

/**
 * `File.delete` reports failure by returning false. Windows refuses to delete a file that is still
 * open or mapped (SQLite maps the `-shm` file), and releases it a moment after the connection is
 * closed; so a refused delete is retried briefly, and a file that stays locked is left to the JVM
 * exit instead of failing a test that already passed.
 */
private fun deleteWithRetry(file: File) {
    repeat(DELETE_ATTEMPTS) {
        if (!file.exists() || file.delete()) return
        Thread.sleep(DELETE_PAUSE_MILLIS)
    }
    file.deleteOnExit()
}

private const val DELETE_ATTEMPTS = 20
private const val DELETE_PAUSE_MILLIS = 50L

internal actual val isApplePlatform: Boolean
    get() = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("ios") }
