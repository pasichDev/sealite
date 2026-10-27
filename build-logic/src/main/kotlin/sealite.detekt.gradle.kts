// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Convention plugin `sealite.detekt`: static analysis of the Kotlin sources of a module.

plugins {
    id("dev.detekt")
}

detekt {
    buildUponDefaultConfig.set(true)
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    parallel.set(true)
}

// Generated sources (build info, golden fixtures) are not ours to lint.
tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    exclude { element -> element.file.invariantSeparatorsPath.contains("/build/") }
}

// With Kotlin Multiplatform detekt 2 registers one task per source set and leaves `detekt` as an empty
// aggregate: make `detekt` (and so `check`) run the source sets that hold our code.
val analysedSourceSets =
    setOf(
        "AndroidDeviceTest",
        "AndroidMain",
        "CommonMain",
        "CommonTest",
        "JvmAndAndroidMain",
        "JvmMain",
        "JvmTest",
        "NativeMain",
        "NativeTest",
    ).map { "detekt${it}SourceSet" }.toSet()

tasks.named("detekt") {
    dependsOn(tasks.matching { it.name in analysedSourceSets })
}
tasks.named("check") {
    dependsOn("detekt")
}
