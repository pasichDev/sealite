// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Convention plugin `sealite.spotless`: formats Kotlin, Gradle scripts and the C glue for the whole
// repository. Apply it to the root project only.

import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    id("com.diffplug.spotless")
}

val ktlintVersion =
    extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("ktlint")
        .get()
        .requiredVersion

val clangFormatVersion = "22.1.8"
val clangFormatAvailable: Boolean =
    try {
        val process = ProcessBuilder("clang-format", "--version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() == 0 && output.contains(clangFormatVersion)
    } catch (_: java.io.IOException) {
        false
    }

configure<SpotlessExtension> {
    kotlin {
        target("sealite/src/**/*.kt", "build-logic/src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "sealite/*.gradle.kts",
            "internal/konan-sysroots/build.gradle.kts",
            "build-logic/*.gradle.kts",
            "build-logic/src/**/*.gradle.kts",
        )
        ktlint(ktlintVersion)
    }
    // The C glue is formatted with clang-format, whose output differs between versions, so the step
    // is registered only when exactly the pinned version is on the PATH (or when -Psealite.requireClangFormat
    // demands it, as the lint job does). Elsewhere `check` does not need the tool installed.
    if (clangFormatAvailable || providers.gradleProperty("sealite.requireClangFormat").isPresent) {
        cpp {
            target("sealite/src/native/**/*.c", "sealite/src/native/**/*.h")
            clangFormat(clangFormatVersion).style("file")
        }
    }
}
