// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

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

internal actual fun publishWithoutReplacing(from: String, to: String) {
    try {
        Os.link(from, to)
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.EEXIST) throw IllegalStateException("$to already exists", e)
        // Without hard links there is no atomic publish that refuses an existing target: fail
        // rather than risk overwriting a file that appears in the meantime.
        throw IOException("Can not publish $to without replacing: ${e.message}", e)
    }
    deleteFileIfExists(from)
}
