// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import java.io.File

internal actual fun makeUnreadable(path: String): Boolean {
    val file = File(path)
    return file.setReadable(false, false) && !file.canRead()
}

internal actual fun listDirectory(directory: String): List<String> = File(directory).list().orEmpty().toList()
