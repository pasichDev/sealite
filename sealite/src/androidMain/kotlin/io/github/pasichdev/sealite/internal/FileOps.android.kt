// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
 * directories), so there is no hard-link publish here.
 *
 * API 30 and later: `renameat2(RENAME_NOREPLACE)`, which renames only when [to] does not exist,
 * atomically in the kernel, against anything that might create [to], Sealite or not. Below API 30
 * the call is never made (the seccomp filter of API 26 to 29 may kill the process on it), and it is
 * skipped when the file system or kernel does not support the flag (`EINVAL`, `ENOSYS`, `EPERM`).
 *
 * Otherwise (API 24 to 29, or the fallback): the check that [to] does not exist and the `rename`
 * happen under one lock that every Sealite publish of [to] takes, an in-process lock (threads) and
 * an exclusive file lock on `.<name>.sealite.lock` next to [to] (processes). The check does not
 * follow symbolic links, so a dangling link at [to] counts as existing. No Sealite publish can
 * replace another's result; unrelated code of the same app that creates [to] without taking the
 * lock can still race with it. The lock file is left in place: deleting it would race with a
 * process about to lock it.
 */
internal actual fun publishWithoutReplacing(from: String, to: String) {
    val target = File(to).absoluteFile
    if (Build.VERSION.SDK_INT >= RENAME_NOREPLACE_API && renamedWithoutReplacing(from, target.path)) return
    publishUnderLock(from, target)
}

/** True if renameat2 renamed the file; false if it can not be used here; throws if [to] exists. */
private fun renamedWithoutReplacing(from: String, to: String): Boolean {
    val errno =
        try {
            NativeLibraryLoader.load()
            renameNoReplace(from, to)
        } catch (_: UnsatisfiedLinkError) {
            return false
        }
    return when (errno) {
        0 -> true
        OsConstants.EEXIST -> throw IllegalStateException("$to already exists")
        OsConstants.EINVAL, OsConstants.ENOSYS, OsConstants.EPERM -> false
        else -> throw IOException("Can not publish $to: ${Os.strerror(errno)}")
    }
}

private fun publishUnderLock(from: String, target: File) {
    val inProcess = PUBLISH_LOCKS.getOrPut(target.path) { Any() }
    synchronized(inProcess) {
        RandomAccessFile(File(target.parentFile, ".${target.name}.sealite.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                if (existsWithoutFollowing(target.path)) throw IllegalStateException("${target.path} already exists")
                try {
                    Os.rename(from, target.path)
                } catch (e: ErrnoException) {
                    throw IOException("Can not publish ${target.path}: ${e.message}", e)
                }
            }
        }
    }
}

/** lstat: a dangling symbolic link exists; only ENOENT means absent. */
private fun existsWithoutFollowing(path: String): Boolean = try {
    Os.lstat(path)
    true
} catch (e: ErrnoException) {
    if (e.errno != OsConstants.ENOENT) throw IOException("Can not look at $path: ${e.message}", e)
    false
}

private const val RENAME_NOREPLACE_API = 30

private val PUBLISH_LOCKS = ConcurrentHashMap<String, Any>()
