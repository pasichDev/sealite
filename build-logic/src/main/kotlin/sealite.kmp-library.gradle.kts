// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

import io.github.pasichdev.sealite.build.GenerateBuildInfoTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

kotlin {
    explicitApi()
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Targets.
    jvm()
    // Android: the AGP KMP library plugin. The JNI .so files (static OpenSSL) come from
    // sealite.native / sealite.cross-natives; commonTest also runs on a device or emulator
    // (the `androidDeviceTest` source set, task `connectedAndroidDeviceTest`).
    android {
        namespace = "io.github.pasichdev.sealite"
        compileSdk = 37
        minSdk = 24
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    // Apple native targets: cinterop over the static SQLCipher build (CommonCrypto, see
    // sealite.native), sharing nativeMain.
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    // jvmAndAndroidMain: the JNI RawDb, shared by the JVM and Android targets.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                // withAndroidTarget() only matches the legacy androidTarget(); the AGP KMP library target
                // is a plain androidJvm target.
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }
}

val generateBuildInfo =
    tasks.register<GenerateBuildInfoTask>("generateBuildInfo") {
        version.set(project.version.toString())
        outputDir.set(layout.buildDirectory.dir("generated/buildinfo/commonMain/kotlin"))
    }

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateBuildInfo.flatMap { it.outputDir })
}
