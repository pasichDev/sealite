// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Not a published module. Its only job is to make Kotlin/Native download the LLVM toolchain and the
// mingw / glibc sysroots under ~/.konan (or KONAN_DATA_DIR) on a machine that has never built a
// Kotlin/Native target for Windows or Linux. :sealite cross-compiles OpenSSL and the JNI libraries
// for those OSes with exactly those files (see CrossToolchains), so its buildOpenSsl* tasks depend
// on `downloadKonanSysroots`.
//
// Kotlin/Native fetches a target's dependencies when it first compiles or links for that target, so
// the tasks below link a trivial executable per target. That is the supported path (the same code
// that a regular linuxX64 / mingwX64 build runs); nothing here reaches into konan's file layout.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    val targets = listOf(linuxX64(), linuxArm64(), mingwX64())
    targets.forEach { target ->
        target.binaries.executable(listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG)) {
            entryPoint = "main"
        }
    }
}

val downloadKonanSysroots =
    tasks.register("downloadKonanSysroots") {
        group = "native"
        description = "Makes Kotlin/Native download the Windows and Linux toolchains and sysroots (~/.konan)."
        dependsOn("linkDebugExecutableLinuxX64", "linkDebugExecutableLinuxArm64", "linkDebugExecutableMingwX64")
    }

// Nothing in this project is a build product: keep `assemble` / `build` of the whole repository from linking it.
tasks.named("assemble") { setDependsOn(emptyList<Any>()) }
