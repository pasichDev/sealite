// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import java.io.File

/**
 * `jni_md.h` for the targets whose JDK is not the host's. `jni.h` itself is platform independent
 * and comes from the running JDK; Android uses the NDK's own `jni.h`.
 */
internal object JniShims {
    private fun shim(exportAttribute: String, importAttribute: String, typedefs: List<String>): String = (
        listOf(
            "#ifndef SEALITE_JNI_MD_H",
            "#define SEALITE_JNI_MD_H",
            "#ifndef JNIEXPORT",
            "#define JNIEXPORT $exportAttribute",
            "#endif",
            "#ifndef JNIIMPORT",
            "#define JNIIMPORT $importAttribute",
            "#endif",
            // JNICALL stays empty: x86-64 has one calling convention, and windows.h-style headers
            // must not see it redefined as __stdcall.
            "#ifndef JNICALL",
            "#define JNICALL",
            "#endif",
        ) + typedefs + "#endif"
    ).joinToString("\n", postfix = "\n")

    private val windows =
        shim(
            "__declspec(dllexport)",
            "__declspec(dllimport)",
            listOf("typedef long jint;", "typedef __int64 jlong;", "typedef signed char jbyte;"),
        )

    private val unix =
        shim(
            "__attribute__((visibility(\"default\")))",
            "__attribute__((visibility(\"default\")))",
            listOf("typedef int jint;", "typedef long jlong;", "typedef signed char jbyte;"),
        )

    fun write(kind: JniKind, dir: File) {
        dir.mkdirs()
        dir.resolve("jni_md.h").writeText(if (kind == JniKind.WINDOWS) windows else unix)
    }
}
