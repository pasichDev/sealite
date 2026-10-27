// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

import io.github.pasichdev.sealite.build.GenerateGoldenFixturesTask

plugins {
    id("sealite.native")
    id("sealite.cross-natives")
    id("sealite.packaging")
    id("sealite.publish")
    id("sealite.detekt")
    alias(libs.plugins.ksp)
}

// The golden databases of testdata/ as Kotlin constants, for the tests of every target.
val generateGoldenFixtures =
    tasks.register<GenerateGoldenFixturesTask>("generateGoldenFixtures") {
        group = "verification"
        description = "Embeds testdata/*.db as ByteArray constants in a generated commonTest source."
        fixtures.from(fileTree(rootProject.layout.projectDirectory.dir("testdata")) { include("*.db") })
        outputDir.set(layout.buildDirectory.dir("generated/goldenFixtures/commonTest/kotlin"))
    }

kotlin {
    sourceSets {
        commonTest {
            kotlin.srcDir(generateGoldenFixtures.flatMap { it.outputDir })
        }
        commonMain.dependencies {
            api(libs.androidx.sqlite)
            // SynchronizedObject for SealiteKey; not exposed in the public API.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// Room is used by tests only, to prove the driver works under Room; KSP generates the database.
dependencies {
    add("kspJvmTest", libs.androidx.room.compiler)
    add("kspJvmTest", libs.androidx.room3.compiler)
    add("kspAndroidDeviceTest", libs.androidx.room.compiler)
}

tasks.withType<Test>().configureEach {
    // The tests exercise the packaged-resource loading path; surface what happens.
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Native code runs in this JVM: be explicit about native access (JDK 24+ warns otherwise).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dokka {
    moduleName.set("Sealite")
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
        sourceLink {
            localDirectory.set(rootProject.layout.projectDirectory.asFile)
            remoteUrl("https://github.com/pasichdev/sealite/blob/main")
            remoteLineSuffix.set("#L")
        }
    }
}

// Broken KDoc links or a malformed Module.md fail the build instead of the release.
tasks.named("check") {
    dependsOn("dokkaGeneratePublicationHtml")
}
