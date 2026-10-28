// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Convention plugin `sealite.coverage`: Kover line and branch coverage of the JVM target, which runs
// the shared commonTest suite plus the JVM-only tests. `koverVerify` (part of `check`) fails below
// 100 %. Kotlin/Native and the Android device tests are not measured: Kover reads JVM bytecode only.
// Apply after `sealite.kmp-library`.

plugins {
    id("org.jetbrains.kotlinx.kover")
}

// Code that cannot run in a test carries this annotation (see ExcludeFromCoverage.kt).
val excludeAnnotation = "io.github.pasichdev.sealite.internal.ExcludeFromCoverage"

kover {
    reports {
        filters {
            excludes {
                annotatedBy(excludeAnnotation)
                // Generated: the build-info constant and the embedded golden fixtures.
                classes("io.github.pasichdev.sealite.BuildInfo*")
                // The Android actuals sit on the JVM report's class path but are not run by the JVM
                // tests (the emulator job covers them): FileOps_androidKt, NativeRenameKt, and the Android
                // NativeLibraryLoader, which shares its class name with the JVM one.
                classes("io.github.pasichdev.sealite.internal.FileOps_androidKt")
                classes("io.github.pasichdev.sealite.internal.NativeRenameKt")
            }
        }
        total {
            xml { onCheck.set(false) }
            html { onCheck.set(false) }
        }
        verify {
            rule("Line and branch coverage of the JVM target") {
                bound {
                    minValue.set(100)
                    coverageUnits.set(kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                    aggregationForGroup.set(kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE)
                }
                bound {
                    minValue.set(100)
                    coverageUnits.set(kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
                    aggregationForGroup.set(kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify", "koverXmlReport", "koverHtmlReport")
}
