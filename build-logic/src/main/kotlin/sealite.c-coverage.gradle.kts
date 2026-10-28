// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Convention plugin `sealite.c-coverage`: an opt-in, local line and branch coverage report of the C
// glue (sealite_jni.c, sealite_key.c; not the SQLCipher amalgamation). Not part of `check` or CI.
//
//   ./gradlew :sealite:cCoverageReport
//
// Builds the macOS JNI library with clang's source-based coverage, runs the JVM tests against it
// (the loader tests are left out: they would load a second, uninstrumented copy) and reads the
// profile with llvm-cov. Needs a Mac whose CPU is arm64. Apply after `sealite.native`.

import io.github.pasichdev.sealite.build.BuildJniLibraryTask
import io.github.pasichdev.sealite.build.FetchSqlCipherTask
import io.github.pasichdev.sealite.build.GenerateRenameHeaderTask
import io.github.pasichdev.sealite.build.NativeFlags

val isMac = System.getProperty("os.name").lowercase().contains("mac")
val prebuilt = providers.gradleProperty("sealite.prebuiltNatives").isPresent

if (isMac && !prebuilt) {
    val nativeBuild = layout.buildDirectory.dir("native")
    val reportDir = layout.buildDirectory.dir("reports/c-coverage")
    val profileDir = reportDir.map { it.dir("profraw") }
    val glueSources = listOf("sealite_jni.c", "sealite_key.c")
    val glueSourceDir = layout.projectDirectory.dir("src/native/jni")

    val buildInstrumented =
        tasks.register<BuildJniLibraryTask>("buildNativeMacosArm64Coverage") {
            group = "verification"
            description = "Builds libsealite_jni.dylib with the glue instrumented for llvm-cov."
            amalgamationDir.set(tasks.named<FetchSqlCipherTask>("fetchSqlCipher").flatMap { it.amalgamationDir })
            renameHeader.set(tasks.named<GenerateRenameHeaderTask>("generateRenameHeader").flatMap { it.header })
            glueDir.set(glueSourceDir)
            jdkHome.set(System.getProperty("java.home"))
            jniIncludeSubdir.set("darwin")
            libName.set("libsealite_jni.dylib")
            targetArgs.set(listOf("--target=arm64-apple-macosx11.0"))
            amalgamationFlags.set(
                NativeFlags.amalgamation("SQLCIPHER_CRYPTO_CC") + "-DSQLITE_EXTRA_INIT=sqlcipher_extra_init",
            )
            // -O0 keeps one source line to one region, which is what a line report needs.
            glueOptimization.set("-O0")
            glueFlags.set(listOf("-fprofile-instr-generate", "-fcoverage-mapping"))
            linkArgs.set(
                listOf(
                    "-framework",
                    "Security",
                    "-framework",
                    "CoreFoundation",
                    "-Wl,-install_name,@rpath/libsealite_jni.dylib",
                    "-fprofile-instr-generate",
                ),
            )
            workDir.set(nativeBuild.map { it.dir("macos_arm64_coverage-obj") })
            outputDir.set(nativeBuild.map { it.dir("macos_arm64_coverage") })
        }

    val instrumentedLibrary = buildInstrumented.flatMap { it.outputDir.file("libsealite_jni.dylib") }

    val testInstrumented =
        tasks.register<Test>("jvmTestCCoverage") {
            group = "verification"
            description = "The JVM tests against the instrumented JNI library; writes llvm profiles."
            val jvmTest = tasks.named<Test>("jvmTest").get()
            testClassesDirs = jvmTest.testClassesDirs
            classpath = jvmTest.classpath
            useJUnitPlatform()
            filter.excludeTestsMatching("*NativeLibraryLoaderTest")
            filter.excludeTestsMatching("*LoaderEnvironmentTest")
            inputs.file(instrumentedLibrary)
            // Always run: the profiles are the output, and a cached result has none.
            outputs.upToDateWhen { false }
            doFirst {
                profileDir.get().asFile.also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
                systemProperty("sealite.library.path", instrumentedLibrary.get().asFile.absolutePath)
                environment(
                    "LLVM_PROFILE_FILE",
                    profileDir
                        .get()
                        .asFile
                        .resolve("sealite-%p.profraw")
                        .absolutePath,
                )
            }
        }

    tasks.register("cCoverageReport") {
        group = "verification"
        description = "Line and branch coverage of the C glue (opt-in, local, not gated)."
        dependsOn(testInstrumented)
        doLast {
            check(System.getProperty("os.arch") == "aarch64") { "needs an arm64 Mac: the library is built for arm64" }

            fun run(command: List<String>, stdout: File? = null) {
                val process = ProcessBuilder(command).redirectErrorStream(stdout == null).inheritIO()
                if (stdout != null) process.redirectOutput(stdout)
                check(process.start().waitFor() == 0) { "failed: ${command.joinToString(" ")}" }
            }
            val raw =
                profileDir
                    .get()
                    .asFile
                    .listFiles { f -> f.extension == "profraw" }
                    .orEmpty()
                    .toList()
            check(raw.isNotEmpty()) { "no .profraw written: the JVM did not exit through exit()" }
            val out = reportDir.get().asFile
            val merged = out.resolve("sealite.profdata")
            run(listOf("xcrun", "llvm-profdata", "merge", "-sparse") + raw.map { it.path } + listOf("-o", merged.path))
            val sources = glueSources.map { glueSourceDir.asFile.resolve(it).absolutePath }
            val lib = instrumentedLibrary.get().asFile.absolutePath
            val textReport = out.resolve("c-coverage.txt")
            run(
                listOf("xcrun", "llvm-cov", "report", lib, "-instr-profile=${merged.path}", "-show-branch-summary") +
                    sources,
                textReport,
            )
            run(
                listOf(
                    "xcrun",
                    "llvm-cov",
                    "show",
                    lib,
                    "-instr-profile=${merged.path}",
                    "-format=html",
                    "-show-branches=count",
                    "-output-dir=${out.resolve("html").path}",
                ) + sources,
            )
            logger.lifecycle(textReport.readText())
            logger.lifecycle("HTML report: ${out.resolve("html/index.html")}")
        }
    }
}
