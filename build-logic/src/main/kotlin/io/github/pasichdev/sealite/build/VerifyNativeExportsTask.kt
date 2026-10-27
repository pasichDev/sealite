// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * `nm -gU` on the built library: the only exported symbols may be JNI entry points. A plain
 * `sqlite3_*` / `sqlcipher_*` name (or anything else) fails the build.
 */
abstract class VerifyNativeExportsTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val library: RegularFileProperty

        /** The `nm` listing, kept as evidence. */
        @get:OutputFile abstract val report: RegularFileProperty

        @TaskAction
        fun run() {
            val out = ByteArrayOutputStream()
            exec.exec {
                commandLine("nm", "-gU", library.get().asFile.absolutePath)
                standardOutput = out
            }
            val listing = out.toString()
            report
                .get()
                .asFile
                .also { it.parentFile.mkdirs() }
                .writeText(listing)

            val exported =
                listing
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map {
                        it
                            .trim()
                            .split(Regex("\\s+"))
                            .last()
                            .removePrefix("_")
                    }.toList()
            val allowed = Regex("""JNI_OnLoad|JNI_OnUnload|Java_.*""")
            val bad = exported.filterNot { allowed.matches(it) }
            if (bad.isNotEmpty()) {
                throw GradleException("Unexpected exported symbols in ${library.get().asFile.name}: $bad")
            }
            val plain = exported.filter { it.startsWith("sqlite3_") || it.startsWith("sqlcipher_") }
            if (plain.isNotEmpty()) throw GradleException("Plain SQLite symbols exported: $plain")
            logger.lifecycle("exported symbols of ${library.get().asFile.name}: $exported")
        }
    }
