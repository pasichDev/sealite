// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal actual fun deleteFileIfExists(path: String) {
    Files.deleteIfExists(Paths.get(path))
}

// createTempFile creates exclusively, with a random name, owner-only on POSIX.
internal actual fun createUniqueFileNextTo(path: String): String {
    val target = File(path).absoluteFile
    return Files.createTempFile(target.parentFile.toPath(), ".${target.name}.sealite-", "").toString()
}

internal actual fun publishWithoutReplacing(from: String, to: String) {
    try {
        linkThenRemoveOrMove(Paths.get(from), Paths.get(to))
    } catch (e: FileAlreadyExistsException) {
        throw IllegalStateException("$to already exists", e)
    }
}

/**
 * Hard-links [target] to [source] and removes [source]. A file system without hard links (FAT,
 * some network shares) gets a move that refuses an existing target instead (checked by the JDK, not
 * atomically). Throws [FileAlreadyExistsException] if [target] exists.
 */
// The fallbacks need a file system without hard links, which no CI host provides.
@ExcludeFromCoverage
private fun linkThenRemoveOrMove(source: Path, target: Path) {
    try {
        Files.createLink(target, source)
    } catch (e: FileAlreadyExistsException) {
        throw e
    } catch (_: UnsupportedOperationException) {
        Files.move(source, target)
        return
    } catch (_: FileSystemException) {
        Files.move(source, target)
        return
    }
    Files.delete(source)
}
