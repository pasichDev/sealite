// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Reads the built library with `llvm-nm` (ELF) or `llvm-objdump` (PE): only `JNI_OnLoad`,
 * `JNI_OnUnload` and `Java_*` may be exported, no `sqlite3_*` / `sqlcipher_*` may be imported or
 * left undefined, and [requiredString] (e.g. `OpenSSL 3.5.9`) must be present, which proves the
 * static libcrypto is inside. The libraries it loads (ELF `NEEDED`, PE DLL imports) must all be on a
 * per-platform allowlist. The listing is written to [report].
 */
abstract class VerifyCrossExportsTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Input abstract val target: Property<String>

        @get:Input @get:Optional
        abstract val requiredString: Property<String>

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val library: RegularFileProperty

        @get:Internal abstract val rootDir: DirectoryProperty

        @get:OutputFile abstract val report: RegularFileProperty

        private fun capture(vararg cmd: String): String {
            val out = ByteArrayOutputStream()
            exec.exec {
                commandLine(*cmd)
                standardOutput = out
            }
            return out.toString()
        }

        @TaskAction
        fun run() {
            val t = CrossTarget.valueOf(target.get())
            val lib = library.get().asFile
            val tools = CrossToolchains.binutils(rootDir.get().asFile)
            val symbols = if (t.isElf) elfSymbols(tools.nm.path, lib) else peSymbols(tools.objdump.path, lib)
            val exported = symbols.exported
            val undefined = symbols.undefined
            val raw = symbols.raw
            report
                .get()
                .asFile
                .also { it.parentFile.mkdirs() }
                .writeText(raw)

            val allowed = Regex("""JNI_OnLoad|JNI_OnUnload|Java_.*""")
            val bad = exported.filterNot { allowed.matches(it) }
            if (bad.isNotEmpty()) {
                throw GradleException(
                    "Unexpected exported symbols in ${lib.name} (${t.dirName}): $bad",
                )
            }
            if (exported.none { it == "JNI_OnLoad" }) {
                throw GradleException(
                    "${lib.name} (${t.dirName}) does not export JNI_OnLoad",
                )
            }
            val leaked = undefined.filter { it.startsWith("sqlite3_") || it.startsWith("sqlcipher_") }
            if (leaked.isNotEmpty()) {
                throw GradleException(
                    "Undefined plain SQLite symbols in ${lib.name} (${t.dirName}): $leaked",
                )
            }

            // What the library loads at run time must be the platform's own libraries and nothing else.
            val needed = neededLibraries(t, tools.objdump.path, lib)
            val allowedLibs = allowedLibraries(t)
            val unexpected = needed.filterNot { name -> allowedLibs.any { it.matches(name) } }
            if (unexpected.isNotEmpty()) {
                throw GradleException(
                    "Unexpected ${if (t.isElf) "NEEDED libraries" else "DLL imports"} in ${lib.name} (${t.dirName}): " +
                        "$unexpected (allowed: ${allowedLibs.map { it.pattern }})",
                )
            }
            report.get().asFile.appendText(
                "# ${if (t.isElf) "needed" else "imported DLLs"}\n${needed.joinToString("\n")}\n",
            )

            val needle = requiredString.orNull
            if (needle != null && !containsBytes(lib, needle.toByteArray(Charsets.US_ASCII))) {
                throw GradleException(
                    "${lib.name} (${t.dirName}) does not contain \"$needle\": libcrypto is not linked in",
                )
            }
            val proof = if (needle != null) ", contains \"$needle\"" else ""
            val summary = "${lib.length() / 1024} KiB, ${exported.size} exports (only JNI), no plain sqlite3_*"
            logger.lifecycle("${t.dirName}/${lib.name}: $summary$proof")
        }

        /** `NEEDED` entries of an ELF file, or the `DLL Name:` imports of a PE file. */
        private fun neededLibraries(t: CrossTarget, objdump: String, lib: File): List<String> {
            val listing = capture(objdump, "-p", lib.path)
            val pattern = if (t.isElf) Regex("""^\s*NEEDED\s+(\S+)""") else Regex("""^\s*DLL Name:\s*(\S+)""")
            return listing
                .lineSequence()
                .mapNotNull { pattern.find(it)?.groupValues?.get(1) }
                .distinct()
                .toList()
        }

        private fun allowedLibraries(t: CrossTarget): List<Regex> = when (t.jni) {
            JniKind.WINDOWS -> {
                listOf(
                    Regex("""(?i)(KERNEL32|ADVAPI32|WS2_32|CRYPT32|USER32|bcrypt|msvcrt)\.dll"""),
                    Regex("""(?i)api-ms-win-crt-[a-z0-9-]+\.dll"""),
                )
            }

            JniKind.UNIX -> {
                listOf(
                    Regex("""libc\.so\.6"""),
                    Regex("""libm\.so\.6"""),
                    Regex("""libdl\.so\.2"""),
                    Regex("""libpthread\.so\.0"""),
                    Regex("""ld-linux-(x86-64|aarch64)\.so\.\d"""),
                )
            }

            JniKind.ANDROID -> {
                listOf(Regex("""libc\.so"""), Regex("""libm\.so"""), Regex("""libdl\.so"""), Regex("""liblog\.so"""))
            }
        }

        private class Symbols(
            val raw: String,
            val exported: List<String>,
            val undefined: List<String>,
        )

        private fun elfSymbols(nm: String, lib: File): Symbols {
            val defined = capture(nm, "-D", "--defined-only", lib.path)
            val undef = capture(nm, "-D", "--undefined-only", lib.path)
            return Symbols("# defined\n$defined# undefined\n$undef", symbolNames(defined), symbolNames(undef))
        }

        private fun symbolNames(listing: String): List<String> = listing
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")).last().substringBefore('@') }
            .toList()

        private fun peSymbols(objdump: String, lib: File): Symbols {
            val raw = capture(objdump, "-p", lib.path)
            // "Export Table" section: "    <ordinal>  0x<rva>  <name>" under the "Ordinal RVA Name" header.
            val exportLine = Regex("""^\s+\d+\s+0x[0-9a-fA-F]+\s+(\S+)""")
            val exported =
                raw
                    .lineSequence()
                    .dropWhile { !it.contains("Ordinal") || !it.contains("RVA") }
                    .drop(1)
                    .map { exportLine.find(it)?.groupValues?.get(1) }
                    .takeWhile { it != null }
                    .filterNotNull()
                    .toList()
            // Import tables list "<hint> <name>" lines; any SQLite name there would be a leak.
            val imports = raw.substringAfter("The Import Tables", "")
            val undefined = Regex("""\b(sqlite3_\w+|sqlcipher_\w+)""").findAll(imports).map { it.value }.toList()
            return Symbols(raw, exported, undefined)
        }

        private fun containsBytes(file: File, needle: ByteArray): Boolean {
            val data = file.readBytes()
            return (0..data.size - needle.size).any { start -> needle.indices.all { data[start + it] == needle[it] } }
        }
    }
