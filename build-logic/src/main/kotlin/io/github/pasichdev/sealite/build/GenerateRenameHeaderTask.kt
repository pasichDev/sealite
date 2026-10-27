// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Generates `sealite_rename.h`: a `#define x sealite_x` for every function or variable the
 * amalgamation exports (`sqlite3_*`, `sqlcipher_*`, and the few other external names SQLCipher
 * leaks, such as `sqlite3pager_*` and `xoshiro_next`).
 *
 * The list is the union of (a) every `SQLITE_API` declaration in `sqlite3.h` and (b) the external
 * symbols of a probe object compiled from `sqlite3.c` with the real flags, so a name added by a
 * SQLCipher or SQLite upgrade is picked up without editing a list by hand.
 */
@CacheableTask
abstract class GenerateRenameHeaderTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val amalgamationDir: DirectoryProperty

        @get:Input abstract val compilerFlags: ListProperty<String>

        @get:Input abstract val compilerArgs: ListProperty<String>

        @get:Input abstract val prefix: Property<String>

        /** Scratch dir for the probe object. */
        @get:Internal abstract val workDir: DirectoryProperty

        @get:OutputFile abstract val header: RegularFileProperty

        @TaskAction
        fun run() {
            val dir = amalgamationDir.get().asFile
            val work = workDir.get().asFile.also { it.mkdirs() }
            val probe = work.resolve("probe.o")
            val sdk = ByteArrayOutputStream()
            exec.exec {
                commandLine("xcrun", "--sdk", "macosx", "--show-sdk-path")
                standardOutput = sdk
            }
            val compileArgs =
                listOf("clang") + compilerArgs.get() + listOf("-isysroot", sdk.toString().trim()) +
                    compilerFlags.get()
            val sourceArgs = listOf("-O0", "-w", "-c", dir.resolve("sqlite3.c").absolutePath)
            exec.exec { commandLine(compileArgs + sourceArgs + listOf("-o", probe.absolutePath)) }
            val nmOut = ByteArrayOutputStream()
            exec.exec {
                commandLine("nm", "-gU", probe.absolutePath)
                standardOutput = nmOut
            }
            // Mach-O symbols carry a leading underscore.
            val fromObject =
                nmOut
                    .toString()
                    .lineSequence()
                    .mapNotNull {
                        it
                            .trim()
                            .split(Regex("\\s+"))
                            .takeIf { p -> p.size >= 3 }
                            ?.last()
                    }.map { it.removePrefix("_") }
                    .toSet()

            val apiDeclaration =
                Regex(
                    """^\s*(?:SQLITE_API|SQLITE_EXTERN)\b[^;{]*?\b((?:sqlite3|sqlcipher)_\w+)\s*(?:\(|\[|;|=)""",
                    RegexOption.MULTILINE,
                )
            val fromHeader =
                apiDeclaration
                    .findAll(dir.resolve("sqlite3.h").readText())
                    .map { it.groupValues[1] }
                    .toSet()

            val names =
                (fromObject + fromHeader)
                    .filter { Regex("[A-Za-z_]\\w*").matches(it) }
                    .toSortedSet()

            val p = prefix.get()
            val out = header.get().asFile
            out.parentFile.mkdirs()
            out.writeText(
                buildString {
                    appendLine("/* Generated. Renames every exported SQLite/SQLCipher symbol so it can not")
                    appendLine("   clash with, or be silently replaced by, another SQLite in the process. */")
                    appendLine("#ifndef SEALITE_RENAME_H")
                    appendLine("#define SEALITE_RENAME_H")
                    for (n in names) appendLine("#define $n $p$n")
                    appendLine("#endif")
                },
            )
            logger.lifecycle("sealite_rename.h: ${names.size} symbols")
        }
    }
