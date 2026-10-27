// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Compiles the SQLCipher amalgamation and the JNI glue into one shared library for one target,
 * with every SQLite/SQLCipher symbol renamed (`-include sealite_rename.h`) and hidden
 * (`-fvisibility=hidden`). Writes `<libName>` and `<libName>.sha256` into [outputDir].
 *
 * Only an Apple-clang toolchain is wired up so far (macOS arm64); other targets will add their own
 * compiler, sysroot and link arguments.
 */
@CacheableTask
abstract class BuildJniLibraryTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val amalgamationDir: DirectoryProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val renameHeader: RegularFileProperty

        /** The glue: `sealite_jni.c`, `sealite_key.c`, `sealite_key.h`. */
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val glueDir: DirectoryProperty

        /** JDK home whose `include/` (and `include/<os>/`) provide `jni.h`. */
        @get:Input abstract val jdkHome: Property<String>

        @get:Input abstract val libName: Property<String>

        @get:Input abstract val amalgamationFlags: ListProperty<String>

        @get:Input abstract val targetArgs: ListProperty<String>

        @get:Input abstract val linkArgs: ListProperty<String>

        @get:Input abstract val jniIncludeSubdir: Property<String>

        /** Extra flags for the glue sources only (the amalgamation is third-party code). */
        @get:Input abstract val glueFlags: ListProperty<String>

        /** Optimisation level of the glue. */
        @get:Input abstract val glueOptimization: Property<String>

        @get:Internal abstract val workDir: DirectoryProperty

        @get:OutputDirectory abstract val outputDir: DirectoryProperty

        init {
            glueOptimization.convention("-O2")
            glueFlags.convention(emptyList())
        }

        @TaskAction
        fun run() {
            val amalgamation = amalgamationDir.get().asFile
            val rename = renameHeader.get().asFile
            val work =
                workDir.get().asFile.also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
            val jdk = File(jdkHome.get())
            val jniIncludes = listOf(jdk.resolve("include"), jdk.resolve("include").resolve(jniIncludeSubdir.get()))
            jniIncludes.forEach { if (!it.isDirectory) throw GradleException("No JNI headers at $it") }

            val sysroot = capture("xcrun", "--sdk", "macosx", "--show-sdk-path").trim()
            val common =
                listOf("clang") + targetArgs.get() +
                    listOf(
                        "-isysroot",
                        sysroot,
                        "-fvisibility=hidden",
                        "-fPIC",
                        "-include",
                        rename.absolutePath,
                        "-I",
                        amalgamation.absolutePath,
                        "-I",
                        glueDir.get().asFile.absolutePath,
                    ) + jniIncludes.flatMap { listOf("-I", it.absolutePath) }

            val objects = mutableListOf<File>()
            // The amalgamation: optimise for speed, warnings off (third-party code).
            val sqliteObject = work.resolve("sqlite3.o")
            exec.exec {
                commandLine(
                    common + amalgamationFlags.get() +
                        listOf(
                            "-O3",
                            "-w",
                            "-c",
                            amalgamation.resolve("sqlite3.c").absolutePath,
                            "-o",
                            sqliteObject.absolutePath,
                        ),
                )
            }
            objects += sqliteObject
            for (source in glueDir
                .get()
                .asFile
                .listFiles()!!
                .filter { it.extension == "c" }
                .sortedBy { it.name }) {
                val obj = work.resolve(source.nameWithoutExtension + ".o")
                exec.exec {
                    // Same -D flags as the amalgamation: they change what sqlite3.h declares (SQLITE_HAS_CODEC).
                    commandLine(
                        common + amalgamationFlags.get() +
                            listOf(
                                glueOptimization.get(),
                                "-Wall",
                                "-Wextra",
                                "-Wno-unused-parameter",
                            ) + glueFlags.get() +
                            listOf(
                                "-c",
                                source.absolutePath,
                                "-o",
                                obj.absolutePath,
                            ),
                    )
                }
                objects += obj
            }

            val out =
                outputDir.get().asFile.also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
            val lib = out.resolve(libName.get())
            exec.exec {
                commandLine(
                    listOf(
                        "clang",
                    ) + targetArgs.get() + listOf("-isysroot", sysroot, "-dynamiclib", "-fvisibility=hidden") +
                        objects.map { it.absolutePath } + linkArgs.get() + listOf("-o", lib.absolutePath),
                )
            }
            out.resolve(libName.get() + ".sha256").writeText(sha256Of(lib) + "\n")
        }

        private fun capture(vararg cmd: String): String {
            val buffer = ByteArrayOutputStream()
            exec.exec {
                commandLine(*cmd)
                standardOutput = buffer
            }
            return buffer.toString()
        }

        private fun sha256Of(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
