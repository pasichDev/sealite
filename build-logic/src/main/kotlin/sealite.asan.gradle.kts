// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Convention plugin `sealite.asan`: an opt-in run of the JVM tests against a JNI library built with
// AddressSanitizer (the SQLCipher amalgamation and the glue) and, for our own C only, UBSan. Not part of
// `check` or the pull-request pipeline; the weekly sanitizers workflow runs it on Linux.
//
//   ./gradlew :sealite:jvmTestAsan                                  (macOS arm64, no other setup)
//   ./gradlew :sealite:jvmTestAsan -Psealite.prebuiltNatives=<dir>  (Linux; <dir>/jvm/natives may be empty)
//
// Catches use-after-free and overflows in the native glue and in SQLite itself, and undefined behaviour in
// the glue. The JVM is started with the sanitizer runtime preloaded (DYLD_INSERT_LIBRARIES on macOS,
// LD_PRELOAD on Linux). macOS needs an arm64 Mac (CommonCrypto); Linux needs clang, its compiler-rt
// (libclang-rt-dev) and the OpenSSL headers (libssl-dev), and builds with the system OpenSSL, which is
// not itself sanitized. Apply after `sealite.native`.

import io.github.pasichdev.sealite.build.BuildJniLibraryTask
import io.github.pasichdev.sealite.build.FetchSqlCipherTask
import io.github.pasichdev.sealite.build.GenerateRenameHeaderTask
import io.github.pasichdev.sealite.build.NativeFlags

val osName = System.getProperty("os.name").lowercase()
val isMac = osName.contains("mac")
val isLinux = osName.contains("linux")
val prebuilt = providers.gradleProperty("sealite.prebuiltNatives").isPresent

// On a Mac the natives are built here, so prebuilt ones mean this is not a local run. On Linux the
// shipped natives are never built (they come from the Mac job), so prebuilt is the normal setup.
if ((isMac && !prebuilt) || isLinux) {
    val nativeBuild = layout.buildDirectory.dir("native")
    val reportDir = layout.buildDirectory.dir("reports/asan")
    val sanitizer = listOf("-fsanitize=address", "-fno-omit-frame-pointer", "-g")
    // UBSan on our own C only: the SQLite amalgamation trips it (signed overflow in sqlite3.c, upstream).
    // No recovery: the first finding aborts the process, so the run fails.
    val ubsan = listOf("-fsanitize=undefined", "-fno-sanitize-recover=all")
    val libName = if (isMac) "libsealite_jni.dylib" else "libsealite_jni.so"
    val dirName = if (isMac) "macos_arm64_asan" else "linux_asan"

    val buildAsan =
        tasks.register<BuildJniLibraryTask>("buildNative${if (isMac) "MacosArm64" else "Linux"}Asan") {
            group = "verification"
            description = "Builds $libName with AddressSanitizer (and UBSan in the glue)."
            amalgamationDir.set(tasks.named<FetchSqlCipherTask>("fetchSqlCipher").flatMap { it.amalgamationDir })
            renameHeader.set(tasks.named<GenerateRenameHeaderTask>("generateRenameHeader").flatMap { it.header })
            glueDir.set(layout.projectDirectory.dir("src/native/jni"))
            jdkHome.set(System.getProperty("java.home"))
            jniIncludeSubdir.set(if (isMac) "darwin" else "linux")
            this.libName.set(libName)
            if (isMac) targetArgs.set(listOf("--target=arm64-apple-macosx11.0"))
            amalgamationFlags.set(
                NativeFlags.amalgamation(if (isMac) "SQLCIPHER_CRYPTO_CC" else "SQLCIPHER_CRYPTO_OPENSSL") +
                    "-DSQLITE_EXTRA_INIT=sqlcipher_extra_init" + sanitizer,
            )
            glueFlags.set(ubsan)
            glueOptimization.set("-O1")
            linkArgs.set(
                if (isMac) {
                    listOf(
                        "-framework",
                        "Security",
                        "-framework",
                        "CoreFoundation",
                        "-Wl,-install_name,@rpath/libsealite_jni.dylib",
                        "-fsanitize=address,undefined",
                    )
                } else {
                    // The runtime is not linked into the library: the JVM preloads it (shared-libsan).
                    listOf("-fsanitize=address", "-shared-libsan", "-lcrypto")
                },
            )
            workDir.set(nativeBuild.map { it.dir("$dirName-obj") })
            outputDir.set(nativeBuild.map { it.dir(dirName) })
        }

    val asanLibrary = buildAsan.flatMap { it.outputDir.file(libName) }

    fun capture(vararg command: String): String =
        ProcessBuilder(*command).redirectErrorStream(false).start().let { process ->
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
                .also { process.waitFor() }
        }

    tasks.register<Test>("jvmTestAsan") {
        group = "verification"
        description = "The JVM tests against the sanitizer-built JNI library (opt-in, not gated by check)."
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
            val report = reportDir.get().asFile.also { it.mkdirs() }
            val options =
                "detect_leaks=0:halt_on_error=1:abort_on_error=0:log_path=" + report.resolve("asan").absolutePath
            if (isMac) {
                val runtime = capture("xcrun", "clang", "--print-file-name=libclang_rt.asan_osx_dynamic.dylib")
                check(File(runtime).isFile) { "no ASan runtime at '$runtime'" }
                // dyld refuses the toolchain's signature inside a hardened JVM ("Sanitizer load violates
                // platform policy"): preload an ad-hoc signed copy instead.
                val preload = report.resolve("libclang_rt.asan_osx_dynamic.dylib")
                File(runtime).copyTo(preload, overwrite = true)
                check(ProcessBuilder("codesign", "-f", "-s", "-", preload.path).inheritIO().start().waitFor() == 0) {
                    "codesign of the ASan runtime failed"
                }
                environment("DYLD_INSERT_LIBRARIES", preload.path)
            } else {
                val arch = if (System.getProperty("os.arch") == "aarch64") "aarch64" else "x86_64"
                val runtime = capture("clang", "--print-file-name=libclang_rt.asan-$arch.so")
                check(File(runtime).isFile) { "no ASan runtime at '$runtime' (install libclang-rt-dev)" }
                environment("LD_PRELOAD", runtime)
            }
            systemProperty("sealite.library.path", asanLibrary.get().asFile.absolutePath)
            environment("ASAN_OPTIONS", options)
        }
    }
}
