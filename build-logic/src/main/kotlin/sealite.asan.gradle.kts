// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Convention plugin `sealite.asan`: an opt-in, local run of the JVM tests against a macOS JNI library
// built with AddressSanitizer (the SQLCipher amalgamation and the glue). Not part of `check` or CI.
//
//   ./gradlew :sealite:jvmTestAsan
//
// Catches use-after-free and overflows in the native glue and in SQLite itself. The JVM is started with
// the ASan runtime preloaded (DYLD_INSERT_LIBRARIES). Needs a Mac whose CPU is arm64. Apply after
// `sealite.native`.

import io.github.pasichdev.sealite.build.BuildJniLibraryTask
import io.github.pasichdev.sealite.build.FetchSqlCipherTask
import io.github.pasichdev.sealite.build.GenerateRenameHeaderTask
import io.github.pasichdev.sealite.build.NativeFlags

val isMac = System.getProperty("os.name").lowercase().contains("mac")
val prebuilt = providers.gradleProperty("sealite.prebuiltNatives").isPresent

if (isMac && !prebuilt) {
    val nativeBuild = layout.buildDirectory.dir("native")
    val reportDir = layout.buildDirectory.dir("reports/asan")
    val sanitizer = listOf("-fsanitize=address", "-fno-omit-frame-pointer", "-g")

    val buildAsan =
        tasks.register<BuildJniLibraryTask>("buildNativeMacosArm64Asan") {
            group = "verification"
            description = "Builds libsealite_jni.dylib with AddressSanitizer."
            amalgamationDir.set(tasks.named<FetchSqlCipherTask>("fetchSqlCipher").flatMap { it.amalgamationDir })
            renameHeader.set(tasks.named<GenerateRenameHeaderTask>("generateRenameHeader").flatMap { it.header })
            glueDir.set(layout.projectDirectory.dir("src/native/jni"))
            jdkHome.set(System.getProperty("java.home"))
            jniIncludeSubdir.set("darwin")
            libName.set("libsealite_jni.dylib")
            targetArgs.set(listOf("--target=arm64-apple-macosx11.0"))
            amalgamationFlags.set(
                NativeFlags.amalgamation("SQLCIPHER_CRYPTO_CC") +
                    "-DSQLITE_EXTRA_INIT=sqlcipher_extra_init" + sanitizer,
            )
            glueOptimization.set("-O1")
            linkArgs.set(
                listOf(
                    "-framework",
                    "Security",
                    "-framework",
                    "CoreFoundation",
                    "-Wl,-install_name,@rpath/libsealite_jni.dylib",
                    "-fsanitize=address",
                ),
            )
            workDir.set(nativeBuild.map { it.dir("macos_arm64_asan-obj") })
            outputDir.set(nativeBuild.map { it.dir("macos_arm64_asan") })
        }

    val asanLibrary = buildAsan.flatMap { it.outputDir.file("libsealite_jni.dylib") }

    tasks.register<Test>("jvmTestAsan") {
        group = "verification"
        description = "The JVM tests against the AddressSanitizer JNI library (opt-in, local, not gated)."
        val jvmTest = tasks.named<Test>("jvmTest").get()
        testClassesDirs = jvmTest.testClassesDirs
        classpath = jvmTest.classpath
        useJUnitPlatform()
        filter.excludeTestsMatching("*NativeLibraryLoaderTest")
        filter.excludeTestsMatching("*LoaderEnvironmentTest")
        inputs.file(asanLibrary)
        outputs.upToDateWhen { false }
        // The sanitizer runtime must be loaded before the JVM maps its first allocation.
        jvmArgs("-Xshare:off")
        doFirst {
            val runtime =
                ProcessBuilder("xcrun", "clang", "--print-file-name=libclang_rt.asan_osx_dynamic.dylib")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            check(File(runtime).isFile) { "no ASan runtime at '$runtime'" }
            // dyld refuses the toolchain's signature inside a hardened JVM ("Sanitizer load violates
            // platform policy"): preload an ad-hoc signed copy instead.
            val preload =
                reportDir
                    .get()
                    .asFile
                    .also { it.mkdirs() }
                    .resolve("libclang_rt.asan_osx_dynamic.dylib")
            File(runtime).copyTo(preload, overwrite = true)
            check(ProcessBuilder("codesign", "-f", "-s", "-", preload.path).inheritIO().start().waitFor() == 0) {
                "codesign of the ASan runtime failed"
            }
            systemProperty("sealite.library.path", asanLibrary.get().asFile.absolutePath)
            reportDir.get().asFile.mkdirs()
            environment("DYLD_INSERT_LIBRARIES", preload.path)
            environment(
                "ASAN_OPTIONS",
                "detect_leaks=0:halt_on_error=1:abort_on_error=0:log_path=" +
                    reportDir
                        .get()
                        .asFile
                        .resolve("asan")
                        .absolutePath,
            )
        }
    }
}
