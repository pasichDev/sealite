// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Convention plugin `sealite.cross-natives`: apply after `sealite.native`. Adds the Windows, Linux and
// Android libraries (static OpenSSL) and wires every native library into the module:
//  - JVM resources `natives/<os>_<arch>/` (library + .sha256) through `packageJvmNatives`;
//  - Android `jniLibs/<abi>/libsealite_jni.so` (only the .so) through `packageAndroidJniLibs`;
//  - `collectNativesForCi`, the layout _natives.yml uploads and `-Psealite.prebuiltNatives` reads.
// With -Psealite.prebuiltNatives=<dir> nothing is compiled: the libraries come from <dir>.

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import io.github.pasichdev.sealite.build.BuildCrossJniLibraryTask
import io.github.pasichdev.sealite.build.CrossTarget
import io.github.pasichdev.sealite.build.PackageJniLibsTask
import io.github.pasichdev.sealite.build.SealiteCrossPlugin

val prebuiltNatives: File? =
    providers
        .gradleProperty("sealite.prebuiltNatives")
        .orNull
        ?.let { File(it).absoluteFile }

val androidAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val nativeBuild = layout.buildDirectory.dir("native")

val packageAndroidJniLibs =
    tasks.register<PackageJniLibsTask>("packageAndroidJniLibs") {
        group = "native"
        description = "Collects libsealite_jni.so for the four Android ABIs under <abi>/."
        outputDir.set(nativeBuild.map { it.dir("android-jniLibs") })
    }

if (prebuiltNatives == null) {
    apply<SealiteCrossPlugin>()

    // The Windows and Linux sysroots live in ~/.konan; on a fresh machine a Kotlin/Native link of
    // those targets downloads them (see internal/konan-sysroots).
    val konanTargets = setOf(CrossTarget.WINDOWS_X64, CrossTarget.LINUX_X64, CrossTarget.LINUX_ARM64)
    for (target in CrossTarget.values()) {
        val suffix = target.taskSuffix
        val verify = tasks.named("verifyCrossExports$suffix")
        if (target in konanTargets) {
            val sysroots = ":internal:konan-sysroots:downloadKonanSysroots"
            tasks.named("buildOpenSsl$suffix") { dependsOn(sysroots) }
            tasks.named("buildCrossNative$suffix") { dependsOn(sysroots) }
            verify.configure { dependsOn(sysroots) }
            val dir = target.dirName
            tasks.named<Sync>("packageJvmNatives") {
                into(
                    "natives/$dir",
                ) { from(tasks.named<BuildCrossJniLibraryTask>("buildCrossNative$suffix").flatMap { it.outputDir }) }
                dependsOn(verify)
            }
        } else {
            val abi = checkNotNull(target.androidAbi)
            packageAndroidJniLibs.configure {
                library(
                    abi,
                    tasks.named<BuildCrossJniLibraryTask>("buildCrossNative$suffix").flatMap {
                        it.outputDir.file(target.libName)
                    },
                )
                dependsOn(verify)
            }
        }
    }
} else {
    packageAndroidJniLibs.configure {
        for (abi in androidAbis) {
            library(abi, layout.file(provider { prebuiltNatives.resolve("android/jniLibs/$abi/libsealite_jni.so") }))
        }
    }
}

// AGP packs the directory as jni/<abi>/libsealite_jni.so of the AAR (and of the device-test APK).
extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(packageAndroidJniLibs, PackageJniLibsTask::outputDir)
}

// What _natives.yml uploads, and what -Psealite.prebuiltNatives=<dir> reads back.
tasks.register<Sync>("collectNativesForCi") {
    group = "native"
    description = "Collects every native library under build/natives-out/{jvm/natives,android/jniLibs,apple}."
    into(layout.buildDirectory.dir("natives-out"))
    into("jvm/natives") { from(tasks.named<Sync>("packageJvmNatives").map { it.destinationDir.resolve("natives") }) }
    into("android/jniLibs") { from(packageAndroidJniLibs.flatMap { it.outputDir }) }
    // Apple static libraries and their headers: built on a Mac only, so the Apple-targets tests and
    // the published klibs use the very files that were verified here.
    into("apple") { from(tasks.named<Sync>("stageAppleForCi").map { it.destinationDir }) }
    dependsOn("packageJvmNatives", packageAndroidJniLibs, "stageAppleForCi")
}
