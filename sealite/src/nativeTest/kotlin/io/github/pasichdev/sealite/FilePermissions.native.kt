// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlinx.cinterop.convert
import platform.Foundation.NSFileManager
import platform.posix.R_OK
import platform.posix.access
import platform.posix.chmod

internal actual fun makeUnreadable(path: String): Boolean = chmod(path, 0.convert()) == 0 && access(path, R_OK) != 0

internal actual fun listDirectory(directory: String): List<String> = NSFileManager.defaultManager
    .contentsOfDirectoryAtPath(directory, error = null)
    .orEmpty()
    .map { it.toString() }
