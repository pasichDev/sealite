// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

internal actual fun deleteFileIfExists(path: String) {
    Files.deleteIfExists(Paths.get(path))
}

internal actual fun moveFileAtomically(from: String, to: String) {
    val target = Paths.get(to)
    // ATOMIC_MOVE may replace an existing target on some platforms: refuse first.
    if (Files.exists(target)) throw java.nio.file.FileAlreadyExistsException(to)
    Files.move(Paths.get(from), target, StandardCopyOption.ATOMIC_MOVE)
}
