// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// Convention plugin `sealite.packaging`: the binary API dump (Kotlin's built-in ABI validation) and
// the licence files inside every published jar. Apply after `sealite.kmp-library`.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// The JVM artifact runs on JDK 17 and later, whatever JDK builds it.
extensions.configure<KotlinMultiplatformExtension> {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }
}

// Public API surface is tracked in <module>/api/: `updateLegacyAbi` rewrites the dump,
// `checkLegacyAbi` (part of `check`) fails when the code and the dump differ.
extensions.configure<KotlinMultiplatformExtension> {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what enables the validation; the defaults are what we want.
    }
}

// LICENSE, NOTICE and the third-party licence texts travel with every jar under META-INF/.
val rootDir = rootProject.layout.projectDirectory
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    from(rootDir.file("LICENSE")) { into("META-INF") }
    from(rootDir.file("NOTICE")) { into("META-INF") }
    from(rootDir.dir("licenses")) { into("META-INF/licenses") }
}

// JVM jar: the shrinker rules under the three META-INF locations ProGuard, R8 and the Android Gradle
// plugin read, the same layout as androidx.sqlite:sqlite-bundled.
val jvmShrinkerRules =
    tasks.register<Sync>("generateJvmShrinkerRules") {
        val rules = layout.projectDirectory.file("src/jvmMain/shrinkerRules/sealite.pro")
        for (dir in listOf(
            "META-INF/proguard",
            "META-INF/com.android.tools/proguard",
            "META-INF/com.android.tools/r8",
        )) {
            into(dir) { from(rules) }
        }
        into(layout.buildDirectory.dir("generated/shrinkerRules/jvmMain"))
    }

// AAR: LICENSE, NOTICE and the third-party texts as Java resources of classes.jar. They go under
// META-INF/licenses/ with distinct names: AGP drops a root META-INF/LICENSE and META-INF/NOTICE.
val androidLicenseResources =
    tasks.register<Sync>("generateAndroidLicenseResources") {
        from(rootDir.file("LICENSE")) {
            into("META-INF/licenses")
            rename { "SEALITE-LICENSE.txt" }
        }
        from(rootDir.file("NOTICE")) {
            into("META-INF/licenses")
            rename { "SEALITE-NOTICE.txt" }
        }
        from(rootDir.dir("licenses")) { into("META-INF/licenses") }
        into(layout.buildDirectory.dir("generated/licenseResources/androidMain"))
    }

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("jvmMain") { resources.srcDir(jvmShrinkerRules.map { it.destinationDir }) }
    sourceSets.named("androidMain") { resources.srcDir(androidLicenseResources.map { it.destinationDir }) }
}
