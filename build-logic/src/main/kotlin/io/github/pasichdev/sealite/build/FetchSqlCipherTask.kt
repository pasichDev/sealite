// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Downloads the pinned SQLCipher release tarball, verifies its sha256, and builds the amalgamation
 * (`sqlite3.c`, `sqlite3.h`) with `./configure && make sqlite3.c` (needs tclsh/cc on the host).
 * The tarball is cached outside the module's build directory so `clean` does not refetch it.
 */
@CacheableTask
abstract class FetchSqlCipherTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Input abstract val url: Property<String>

        @get:Input abstract val sha256: Property<String>

        /** Where the downloaded tarball is kept. */
        @get:Internal abstract val downloadDir: DirectoryProperty

        /** Scratch space for the extracted tree and the out-of-tree build. */
        @get:Internal abstract val workDir: DirectoryProperty

        /** Receives `sqlite3.c` and `sqlite3.h`. */
        @get:OutputDirectory abstract val amalgamationDir: DirectoryProperty

        @TaskAction
        fun run() {
            val tarball =
                downloadDir
                    .get()
                    .asFile
                    .also { it.mkdirs() }
                    .resolve("sqlcipher-${sha256.get().take(12)}.tar.gz")
            if (!tarball.isFile || sha256Of(tarball) != sha256.get()) {
                logger.lifecycle("Downloading ${url.get()}")
                val tmp = File(tarball.path + ".part")
                URI(url.get()).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(tarball)
            }
            val actual = sha256Of(tarball)
            if (actual != sha256.get()) {
                tarball.delete()
                throw GradleException("sha256 mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
            }

            val work = workDir.get().asFile
            work.deleteRecursively()
            work.mkdirs()
            exec.exec {
                commandLine("tar", "-xzf", tarball.absolutePath, "-C", work.absolutePath)
            }
            val src = work.listFiles()!!.single { it.isDirectory }
            val build = File(work, "bld").also { it.mkdirs() }
            exec.exec {
                workingDir(build)
                commandLine(
                    src.resolve("configure").absolutePath,
                    "--with-tempstore=yes",
                    "CFLAGS=-DSQLITE_HAS_CODEC",
                )
                standardOutput = build.resolve("configure.log").outputStream()
            }
            exec.exec {
                workingDir(build)
                commandLine("make", "sqlite3.c")
                standardOutput = build.resolve("make.log").outputStream()
            }
            val out = amalgamationDir.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            for (name in listOf("sqlite3.c", "sqlite3.h")) {
                build.resolve(name).copyTo(out.resolve(name), overwrite = true)
            }
        }

        private fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
