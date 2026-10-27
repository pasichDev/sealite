// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Compiles the SQLCipher amalgamation (OpenSSL crypto provider) and the JNI glue into one shared
 * library for one cross target, every SQLite/SQLCipher symbol renamed (`-include sealite_rename.h`)
 * and hidden (`-fvisibility=hidden`), linked against the static `libcrypto.a` from
 * [BuildOpenSslTask]. Writes `<libName>` and `<libName>.sha256` into [outputDir].
 */
abstract class BuildCrossJniLibraryTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Input abstract val target: Property<String>

        @get:Input abstract val toolchainFingerprint: Property<String>

        @get:Input abstract val amalgamationFlags: ListProperty<String>

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val amalgamationDir: DirectoryProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val renameHeader: RegularFileProperty

        /** The glue: `*.c` and `*.h` files. */
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val glueDir: DirectoryProperty

        /** Output of [BuildOpenSslTask]: `include/` and `lib/libcrypto.a`. */
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val opensslDir: DirectoryProperty

        /** JDK whose `include/jni.h` is used for the Windows and Linux targets. */
        @get:Input @get:Optional
        abstract val jdkHome: Property<String>

        @get:Internal abstract val rootDir: DirectoryProperty

        @get:Internal abstract val workDir: DirectoryProperty

        @get:OutputDirectory abstract val outputDir: DirectoryProperty

        @TaskAction
        fun run() {
            val started = System.nanoTime()
            val t = CrossTarget.valueOf(target.get())
            val tc = CrossToolchains.resolve(t, rootDir.get().asFile)
            val amalgamation = amalgamationDir.get().asFile
            val glue = glueDir.get().asFile
            val openssl = opensslDir.get().asFile
            val work =
                workDir.get().asFile.also {
                    it.deleteRecursively()
                    it.mkdirs()
                }

            val jniIncludes =
                when (t.jni) {
                    JniKind.ANDROID -> {
                        emptyList()
                    }

                    // the NDK sysroot has a self-contained jni.h
                    else -> {
                        val jdk = File(jdkHome.orNull ?: System.getProperty("java.home"))
                        val include = jdk.resolve("include")
                        if (!include.resolve("jni.h").isFile) throw GradleException("No jni.h at $include")
                        JniShims.write(t.jni, work.resolve("jni-shim"))
                        listOf(include, work.resolve("jni-shim"))
                    }
                }

            val common =
                listOf(tc.cc.path) + tc.compileArgs +
                    listOf(
                        "-fvisibility=hidden",
                        "-include",
                        renameHeader.get().asFile.absolutePath,
                        "-I",
                        amalgamation.absolutePath,
                        "-I",
                        glue.absolutePath,
                        "-I",
                        openssl.resolve("include").absolutePath,
                    ) + jniIncludes.flatMap { listOf("-I", it.absolutePath) }

            val objects = mutableListOf<File>()
            val sqliteObject = work.resolve("sqlite3.o")
            exec.runLogged(
                common + amalgamationFlags.get() +
                    listOf("-O3", "-w", "-c", amalgamation.resolve("sqlite3.c").path, "-o", sqliteObject.path),
                work,
                emptyMap(),
                work.resolve("sqlite3.log"),
                "compiling sqlite3.c for ${t.dirName}",
            )
            objects += sqliteObject
            for (source in glue.listFiles()!!.filter { it.extension == "c" }.sortedBy { it.name }) {
                val obj = work.resolve(source.nameWithoutExtension + ".o")
                exec.runLogged(
                    // Same -D flags as the amalgamation: they change what sqlite3.h declares (SQLITE_HAS_CODEC).
                    common + amalgamationFlags.get() +
                        listOf("-O2", "-Wall", "-Wextra", "-Wno-unused-parameter", "-c", source.path, "-o", obj.path),
                    work,
                    emptyMap(),
                    work.resolve("${source.nameWithoutExtension}.log"),
                    "compiling ${source.name} for ${t.dirName}",
                )
                objects += obj
            }

            val out =
                outputDir.get().asFile.also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
            val lib = out.resolve(t.libName)
            // ELF: export the JNI entry points only (this also hides _init/_fini and any libcrypto symbol).
            val exportArgs =
                if (t.isElf) {
                    val map = work.resolve("exports.map")
                    map.writeText("{ global: JNI_On*; Java_*; local: *; };\n")
                    listOf("-Wl,--version-script=${map.path}")
                } else {
                    emptyList()
                }
            exec.runLogged(
                listOf(tc.cc.path) + tc.compileArgs + tc.linkArgs + exportArgs + objects.map { it.path } +
                    listOf(openssl.resolve("lib/libcrypto.a").path) + tc.linkLibs + listOf("-o", lib.path),
                work,
                emptyMap(),
                work.resolve("link.log"),
                "linking ${t.libName} for ${t.dirName}",
            )
            out.resolve(t.libName + ".sha256").writeText(sha256Hex(lib) + "\n")
            work.deleteRecursively()
            val seconds = (System.nanoTime() - started) / 1_000_000_000
            logger.lifecycle("${t.dirName}/${t.libName}: ${lib.length() / 1024} KiB in ${seconds}s")
        }
    }
