// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the loader leaves on disk when its JVM exits; that needs a JVM of its own. */
class LoaderExitTest {
    private val cleanUp = mutableListOf<File>()

    @AfterTest
    fun removeTemporaryFiles() {
        cleanUp.forEach { it.deleteRecursively() }
        cleanUp.clear()
    }

    private val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile.also {
        cleanUp += it
    }

    @Test
    fun theFreshPrivateDirectoryIsRemovedWhenTheJvmExits() {
        // Windows keeps a loaded library locked, and reaches the fresh directory only after an I/O error.
        if (!posix) return
        val temp = tempDir("sealite-tmp")
        // A home that is a file: no cache directory can exist under it, so the loader falls back.
        val home = File(tempDir("sealite-home"), "home").also { it.writeText("not a directory") }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                "-Djava.io.tmpdir=${temp.path}",
                "-Duser.home=${home.path}",
                "io.github.pasichdev.sealite.LoaderChildJvmKt",
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the child JVM did not exit")
        assertEquals(0, process.exitValue(), output)

        val extracted = File(output.trim().lines().last())
        assertEquals(temp, extracted.parentFile.parentFile.canonicalFile, output)
        assertTrue(extracted.parentFile.name.startsWith("sealite-"), output)
        assertEquals(emptyList(), temp.list().orEmpty().toList(), "left in the temporary directory")
    }
}
