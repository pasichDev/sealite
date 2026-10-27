// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException

// java.nio.file needs API 26 and the library supports API 24: java.io and android.system.Os only.

internal actual fun deleteFileIfExists(path: String) {
    val file = File(path)
    if (!file.delete() && file.exists()) throw IOException("Cannot delete $path")
}

internal actual fun moveFileAtomically(from: String, to: String) {
    // rename(2) replaces an existing target: refuse first.
    if (File(to).exists()) throw IOException("$to already exists")
    try {
        Os.rename(from, to)
    } catch (e: ErrnoException) {
        throw IOException("Cannot rename $from to $to: ${e.message}", e)
    }
}
