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
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * `nm -g --defined-only` on a static archive: every symbol the archive defines must carry the
 * `sealite_` prefix. A plain `_sqlite3_*` / `_sqlcipher_*` definition would clash with, or silently
 * replace, another SQLite in the same binary. `nm -u` must list no plain `sqlite3_*` /
 * `sqlcipher_*` either: such a reference would bind to a foreign SQLite.
 */
@DisableCachingByDefault(because = "Cheap and reads host tools")
abstract class VerifyStaticSymbolsTask
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
                commandLine("nm", "-g", "--defined-only", library.get().asFile.absolutePath)
                standardOutput = out
            }
            val listing = out.toString()
            report
                .get()
                .asFile
                .also { it.parentFile.mkdirs() }
                .writeText(listing)

            val defined =
                listing
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.endsWith(":") }
                    .map { it.split(Regex("\\s+")).last() }
                    .toList()
            if (defined.isEmpty()) throw GradleException("No symbols found in ${library.get().asFile.name}")
            val bad = defined.filterNot { it.startsWith("_sealite_") }
            if (bad.isNotEmpty()) {
                throw GradleException("Unprefixed symbols defined in ${library.get().asFile.name}: $bad")
            }

            // A reference to a plain sqlite3_* / sqlcipher_* would bind to whatever SQLite is in the
            // process (the system's, another library's) instead of Sealite's own copy.
            val undefinedOut = ByteArrayOutputStream()
            exec.exec {
                commandLine("nm", "-u", library.get().asFile.absolutePath)
                standardOutput = undefinedOut
            }
            val plain = Regex("""^_?(sqlite3|sqlcipher)_""")
            val leaked =
                undefinedOut
                    .toString()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.endsWith(":") }
                    .map { it.split(Regex("\\s+")).last() }
                    .filter { plain.containsMatchIn(it) }
                    .toList()
            if (leaked.isNotEmpty()) {
                throw GradleException("Undefined plain SQLite symbols in ${library.get().asFile.name}: $leaked")
            }
            logger.lifecycle(
                "${library.get().asFile.name}: ${defined.size} defined symbols, all sealite_-prefixed, " +
                    "no undefined plain sqlite3_ / sqlcipher_",
            )
        }
    }
