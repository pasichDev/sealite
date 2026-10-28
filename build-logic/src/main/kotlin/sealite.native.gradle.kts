// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

import io.github.pasichdev.sealite.build.BuildJniLibraryTask
import io.github.pasichdev.sealite.build.BuildStaticLibraryTask
import io.github.pasichdev.sealite.build.FetchSqlCipherTask
import io.github.pasichdev.sealite.build.GenerateRenameHeaderTask
import io.github.pasichdev.sealite.build.NativeFlags
import io.github.pasichdev.sealite.build.VerifyNativeExportsTask
import io.github.pasichdev.sealite.build.VerifyStaticSymbolsTask
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    id("sealite.kmp-library")
}

// SQLCipher Community v4.20.0 (stable, 2026-10-27; tag commit 355d4db92d3404e71faa8088d071a71d767807f4,
// SQLite 3.54.1). The tarball sha256 pins the exact bytes that get built.
val sqlcipherVersion = "4.20.0"
val sqlcipherSha256 = "38705f51b61ac0636a5fd064f9a77fafabc7227b2aa2513f1b14c8bb1102affc"

val nativeBuild = layout.buildDirectory.dir("native")
val glueSourceDir = layout.projectDirectory.dir("src/native/jni")

val fetchSqlCipher =
    tasks.register<FetchSqlCipherTask>("fetchSqlCipher") {
        group = "native"
        description = "Downloads SQLCipher $sqlcipherVersion, verifies its sha256 and builds the amalgamation."
        url.set("https://github.com/sqlcipher/sqlcipher/archive/refs/tags/v$sqlcipherVersion.tar.gz")
        sha256.set(sqlcipherSha256)
        // Outside the module's build dir, so `clean` does not download the tarball again.
        downloadDir.set(rootProject.layout.buildDirectory.dir("native-downloads"))
        workDir.set(nativeBuild.map { it.dir("sqlcipher-work") })
        amalgamationDir.set(nativeBuild.map { it.dir("sqlcipher-amalgamation") })
    }

// ---- Prebuilt natives (CI) ----------------------------------------------------------------------
// -Psealite.prebuiltNatives=<dir> skips every JNI/OpenSSL compilation and takes the libraries from
// <dir>, laid out as `collectNativesForCi` writes (and _natives.yml uploads) them:
//   <dir>/jvm/natives/<os>_<arch>/{lib, lib.sha256}     JVM resources
//   <dir>/android/jniLibs/<abi>/libsealite_jni.so       Android jniLibs
//   <dir>/apple/<target>/libsealite.a                   Apple static libraries (cinterop)
//   <dir>/apple/include/{sqlite3.h, sealite_rename.h}   the headers those libraries were built with
// Without <dir>/apple the Apple static libraries are built here, which needs a Mac. On any other host
// the Apple targets are disabled, so nothing Apple-related is wired in (no Xcode tool is reachable).
val prebuiltNatives: File? =
    providers
        .gradleProperty("sealite.prebuiltNatives")
        .orNull
        ?.let { File(it).absoluteFile }
        ?.also { require(it.isDirectory) { "-Psealite.prebuiltNatives: $it is not a directory" } }

val appleHost = OperatingSystem.current().isMacOsX
val prebuiltApple: File? = prebuiltNatives?.resolve("apple")?.takeIf { it.isDirectory }

val generateRenameHeader =
    tasks.register<GenerateRenameHeaderTask>("generateRenameHeader") {
        group = "native"
        description = "Generates sealite_rename.h (sqlite3_* / sqlcipher_* -> sealite_*)."
        amalgamationDir.set(fetchSqlCipher.flatMap { it.amalgamationDir })
        compilerArgs.set(listOf("--target=arm64-apple-macosx11.0"))
        compilerFlags.set(NativeFlags.amalgamation("SQLCIPHER_CRYPTO_CC") + "-DSQLITE_EXTRA_INIT=sqlcipher_extra_init")
        prefix.set("sealite_")
        workDir.set(nativeBuild.map { it.dir("rename-probe") })
        header.set(nativeBuild.map { it.file("generated/sealite_rename.h") })
    }

// ---- Packaging into JVM resources: natives/<os>_<arch>/ -----------------------------------------
val packageJvmNatives =
    tasks.register<Sync>("packageJvmNatives") {
        group = "native"
        description = "Collects the built libraries and their .sha256 files under natives/<os>_<arch>/."
        into(nativeBuild.map { it.dir("jvm-resources") })
        if (prebuiltNatives != null) {
            val prebuiltJvm = prebuiltNatives.resolve("jvm/natives")
            doFirst {
                require(
                    prebuiltJvm.isDirectory,
                ) { "-Psealite.prebuiltNatives: no jvm/natives in $prebuiltNatives" }
            }
            into("natives") { from(prebuiltJvm) }
        }
    }

kotlin.sourceSets.named("jvmMain") {
    resources.srcDir(packageJvmNatives.map { it.destinationDir })
}

// ---- macOS JVM libraries (CommonCrypto), built with Xcode's clang ------------------------------
// One per CPU: arm64 and x86_64 (Intel Macs) from the same host. Skipped with prebuilt natives.
class MacosJvmTarget(
    val taskSuffix: String,
    val dirName: String,
    val triple: String,
)

if (prebuiltNatives == null) {
    val macosFlags = NativeFlags.amalgamation("SQLCIPHER_CRYPTO_CC") + "-DSQLITE_EXTRA_INIT=sqlcipher_extra_init"
    for (
    mac in
    listOf(
        MacosJvmTarget("MacosArm64", "macos_arm64", "arm64-apple-macosx11.0"),
        MacosJvmTarget("MacosX64", "macos_x64", "x86_64-apple-macosx11.0"),
    )
    ) {
        val build =
            tasks.register<BuildJniLibraryTask>("buildNative${mac.taskSuffix}") {
                group = "native"
                description = "Builds libsealite_jni.dylib for ${mac.dirName}."
                amalgamationDir.set(fetchSqlCipher.flatMap { it.amalgamationDir })
                renameHeader.set(generateRenameHeader.flatMap { it.header })
                glueDir.set(glueSourceDir)
                jdkHome.set(System.getProperty("java.home"))
                jniIncludeSubdir.set("darwin")
                libName.set("libsealite_jni.dylib")
                targetArgs.set(listOf("--target=${mac.triple}"))
                amalgamationFlags.set(macosFlags)
                linkArgs.set(
                    listOf(
                        "-framework",
                        "Security",
                        "-framework",
                        "CoreFoundation",
                        "-Wl,-install_name,@rpath/libsealite_jni.dylib",
                    ),
                )
                workDir.set(nativeBuild.map { it.dir("${mac.dirName}-obj") })
                outputDir.set(nativeBuild.map { it.dir(mac.dirName) })
            }

        val verify =
            tasks.register<VerifyNativeExportsTask>("verifyNativeExports${mac.taskSuffix}") {
                group = "verification"
                description = "Fails if the ${mac.dirName} library exports anything but JNI entry points."
                library.set(build.flatMap { it.outputDir.file("libsealite_jni.dylib") })
                report.set(nativeBuild.map { it.file("${mac.dirName}-exports.txt") })
            }

        packageJvmNatives.configure {
            val osxDir = mac.dirName.replace("macos_", "osx_")
            into("natives/$osxDir") { from(build.flatMap { it.outputDir }) }
            dependsOn(verify)
        }
        tasks.named("check") { dependsOn(verify) }
    }
}

// ---- Apple native targets: static libsealite.a + cinterop ---------------------------------------
// One static archive per Kotlin/Native target, built with Xcode's clang (so the build host is a Mac).
// The cinterop klib embeds the archive (-staticLibrary), so a consumer links nothing by hand.
class AppleNativeTarget(
    val taskSuffix: String,
    val dirName: String,
    val sdk: String,
    val triple: String,
)

val appleNativeTargets =
    mapOf(
        KonanTarget.IOS_ARM64 to AppleNativeTarget("IosArm64", "ios_arm64", "iphoneos", "arm64-apple-ios14.0"),
        KonanTarget.IOS_SIMULATOR_ARM64 to
            AppleNativeTarget(
                "IosSimulatorArm64",
                "ios_simulator_arm64",
                "iphonesimulator",
                "arm64-apple-ios14.0-simulator",
            ),
        KonanTarget.MACOS_ARM64 to
            AppleNativeTarget(
                "MacosArm64",
                "macos_arm64_static",
                "macosx",
                "arm64-apple-macosx11.0",
            ),
    )

val appleStaticFlags = NativeFlags.amalgamation("SQLCIPHER_CRYPTO_CC")

// What `collectNativesForCi` ships as <dir>/apple: the static libraries and the headers they were built with.
val stageAppleForCi =
    tasks.register<Sync>("stageAppleForCi") {
        group = "native"
        description = "Collects libsealite.a for every Apple target and the headers they were built with."
        into(nativeBuild.map { it.dir("apple-out") })
        into("include") {
            from(fetchSqlCipher.flatMap { it.amalgamationDir }) { include("sqlite3.h") }
            from(generateRenameHeader.flatMap { it.header })
        }
    }

kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    val apple = appleNativeTargets[konanTarget] ?: return@configureEach

    // Where the cinterop finds libsealite.a and the headers: the prebuilt ones, the ones built here
    // (Mac), or, on a host that can not run the Apple tasks anyway, the paths those tasks would use.
    val libraryDir: Provider<File>
    val includeDirs: Provider<List<File>>
    val verify: TaskProvider<VerifyStaticSymbolsTask>?
    if (prebuiltApple != null) {
        libraryDir = provider { prebuiltApple.resolve(apple.dirName) }
        includeDirs = provider { listOf(prebuiltApple.resolve("include")) }
        verify = null
    } else {
        val build =
            tasks.register<BuildStaticLibraryTask>("buildStatic${apple.taskSuffix}") {
                group = "native"
                description = "Builds the static libsealite.a for ${apple.dirName}."
                amalgamationDir.set(fetchSqlCipher.flatMap { it.amalgamationDir })
                renameHeader.set(generateRenameHeader.flatMap { it.header })
                sdk.set(apple.sdk)
                targetArgs.set(listOf("--target=${apple.triple}"))
                amalgamationFlags.set(appleStaticFlags)
                workDir.set(nativeBuild.map { it.dir("${apple.dirName}-obj") })
                library.set(nativeBuild.map { it.file("${apple.dirName}/libsealite.a") })
            }
        verify =
            tasks.register<VerifyStaticSymbolsTask>("verifyStaticSymbols${apple.taskSuffix}") {
                group = "verification"
                description = "Fails if libsealite.a for ${apple.dirName} defines a symbol without the sealite_ prefix."
                library.set(build.flatMap { it.library })
                report.set(nativeBuild.map { it.file("${apple.dirName}-symbols.txt") })
            }
        libraryDir = nativeBuild.map { it.dir(apple.dirName).asFile }
        // sqlite3.h from the amalgamation (not the SDK's), and the rename header it is parsed with.
        includeDirs =
            provider {
                listOf(
                    fetchSqlCipher
                        .flatMap { it.amalgamationDir }
                        .get()
                        .asFile,
                    generateRenameHeader
                        .flatMap { it.header }
                        .get()
                        .asFile.parentFile,
                )
            }
        if (appleHost) {
            tasks.named("check") { dependsOn(verify) }
            stageAppleForCi.configure {
                into(apple.dirName) { from(build.flatMap { it.library }) }
                dependsOn(verify)
            }
        }
    }

    compilations.getByName("main").cinterops.create("sealite") {
        includeDirs(*includeDirs.get().toTypedArray())
        extraOpts(
            "-libraryPath",
            libraryDir.get().absolutePath,
            "-staticLibrary",
            "libsealite.a",
        )
        // Only a Mac runs the cinterop of an Apple target. Elsewhere the task is disabled, but Gradle
        // still runs what it depends on, which would reach xcrun.
        if (verify != null && appleHost) {
            tasks.named(interopProcessingTaskName) { dependsOn(verify) }
        }
    }

    // Test only: the system libsqlite3 next to libsealite.a (see SystemSqliteCoexistenceTest).
    compilations.getByName("test").cinterops.create("systemSqlite")
}

// cinterop bindings are marked experimental; the Apple binding code uses them throughout.
kotlin.sourceSets
    .matching { ss ->
        listOf("native", "apple", "ios", "macos").any { ss.name.startsWith(it) }
    }.configureEach {
        languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }
