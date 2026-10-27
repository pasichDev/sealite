// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Compiles the SQLCipher amalgamation with Xcode's clang into a static `libsealite.a` for one Apple
 * target (iOS device, iOS simulator, macOS). Every SQLite/SQLCipher symbol is renamed
 * (`-include sealite_rename.h`) and hidden (`-fvisibility=hidden`), so the archive defines only
 * `_sealite_sqlite3_*` / `_sealite_sqlcipher_*` names.
 *
 * The archive is embedded into the cinterop klib, so consumers need no extra setup.
 */
@DisableCachingByDefault(because = "Output depends on the Xcode toolchain installed on the build host")
abstract class BuildStaticLibraryTask
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

        /** `iphoneos`, `iphonesimulator` or `macosx`: the argument of `xcrun --sdk`. */
        @get:Input abstract val sdk: Property<String>

        /** `--target=...` and the like. */
        @get:Input abstract val targetArgs: ListProperty<String>

        @get:Input abstract val amalgamationFlags: ListProperty<String>

        @get:Internal abstract val workDir: DirectoryProperty

        @get:OutputFile abstract val library: RegularFileProperty

        @TaskAction
        fun run() {
            val amalgamation = amalgamationDir.get().asFile
            val work =
                workDir.get().asFile.also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
            val sysroot = capture("xcrun", "--sdk", sdk.get(), "--show-sdk-path").trim()
            val obj = work.resolve("sqlite3.o")
            val compileArgs =
                listOf("xcrun", "--sdk", sdk.get(), "clang") + targetArgs.get() +
                    listOf(
                        "-isysroot",
                        sysroot,
                        "-fvisibility=hidden",
                        "-include",
                        renameHeader.get().asFile.absolutePath,
                        "-I",
                        amalgamation.absolutePath,
                    ) + amalgamationFlags.get()
            val sourceArgs = listOf("-O3", "-w", "-c", amalgamation.resolve("sqlite3.c").absolutePath)
            exec.exec { commandLine(compileArgs + sourceArgs + listOf("-o", obj.absolutePath)) }
            val lib = library.get().asFile
            lib.parentFile.mkdirs()
            lib.delete()
            // -D: deterministic archive (no timestamps / uids).
            exec.exec { commandLine("xcrun", "libtool", "-static", "-D", "-o", lib.absolutePath, obj.absolutePath) }
        }

        private fun capture(vararg cmd: String): String {
            val buffer = ByteArrayOutputStream()
            exec.exec {
                commandLine(*cmd)
                standardOutput = buffer
            }
            return buffer.toString()
        }
    }
