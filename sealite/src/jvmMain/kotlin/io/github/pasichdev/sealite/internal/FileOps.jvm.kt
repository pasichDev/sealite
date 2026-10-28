// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
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
    val source = Paths.get(from)
    try {
        createHardLink(source, Paths.get(to))
    } catch (e: FileAlreadyExistsException) {
        throw IllegalStateException("$to already exists", e)
    }
    Files.delete(source)
}

// The failure paths need a file system without hard links (FAT, some network shares), which no CI
// host provides; the case that is testable, an existing target, passes through untouched.
@ExcludeFromCoverage
@Suppress("ThrowsCount")
private fun createHardLink(source: java.nio.file.Path, target: java.nio.file.Path) {
    try {
        Files.createLink(target, source)
    } catch (e: FileAlreadyExistsException) {
        throw e
    } catch (e: UnsupportedOperationException) {
        throw noHardLinks(target.toString(), e)
    } catch (e: FileSystemException) {
        throw noHardLinks(target.toString(), e)
    }
}

/**
 * Without hard links there is no atomic way to publish a file that refuses an existing target, so
 * the migration fails rather than risk overwriting a file that appears in the meantime.
 */
@ExcludeFromCoverage
private fun noHardLinks(to: String, cause: Exception): IOException =
    IOException("Can not publish $to without replacing: the file system has no hard links", cause)
