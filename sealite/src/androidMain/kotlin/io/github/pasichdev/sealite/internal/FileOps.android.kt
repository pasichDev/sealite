// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

// java.nio.file needs API 26 and the library supports API 24: java.io and android.system.Os only.

internal actual fun deleteFileIfExists(path: String) {
    val file = File(path)
    if (!file.delete() && file.exists()) throw IOException("Cannot delete $path")
}

// File.createTempFile creates exclusively, with a random name, in the app's own directory.
internal actual fun createUniqueFileNextTo(path: String): String {
    val target = File(path).absoluteFile
    return File.createTempFile(".${target.name}.sealite-", "", target.parentFile).path
}

/**
 * Android's SELinux policy denies hard links to apps (`link` fails with EACCES in the app's own
 * directories), and `renameat2(RENAME_NOREPLACE)` is not available below API 30. So the check that
 * [to] does not exist and the `rename` happen under one lock that every Sealite publish of [to]
 * takes: an in-process lock (threads) and an exclusive file lock on `.<name>.sealite.lock` next to
 * [to] (processes). No Sealite publish can replace another's result. The directory is private to
 * the app's UID, so only the app's own code, bypassing Sealite, could create [to] in between.
 * The lock file is left in place: deleting it would race with a process about to lock it.
 */
internal actual fun publishWithoutReplacing(from: String, to: String) {
    val target = File(to).absoluteFile
    val inProcess = PUBLISH_LOCKS.getOrPut(target.path) { Any() }
    synchronized(inProcess) {
        RandomAccessFile(File(target.parentFile, ".${target.name}.sealite.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                if (target.exists()) throw IllegalStateException("$to already exists")
                try {
                    Os.rename(from, target.path)
                } catch (e: ErrnoException) {
                    throw IOException("Can not publish $to: ${e.message}", e)
                }
            }
        }
    }
}

private val PUBLISH_LOCKS = ConcurrentHashMap<String, Any>()
