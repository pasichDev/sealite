pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            // Versions shared with the library (Kotlin, KSP, Room, androidx.sqlite, coroutines).
            from(files("../gradle/libs.versions.toml"))

            // Sample-only additions.
            version("composeMultiplatform", "1.12.2")
            version("activityCompose", "1.11.0")
            version("kotlinxIo", "0.9.1")

            plugin("compose-multiplatform", "org.jetbrains.compose").versionRef("composeMultiplatform")
            plugin("compose-compiler", "org.jetbrains.kotlin.plugin.compose").versionRef("kotlin")
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
            plugin("android-application", "com.android.application").versionRef("agp")

            library("kotlinx-io-core", "org.jetbrains.kotlinx", "kotlinx-io-core").versionRef("kotlinxIo")
            library("androidx-activity-compose", "androidx.activity", "activity-compose").versionRef("activityCompose")
        }
    }
}

// The library is built from the repository root as an included build; nothing is published.
// -Psealite.root=<dir> points at another checkout of the library (default: this repository).
includeBuild(providers.gradleProperty("sealite.root").getOrElse("..")) {
    dependencySubstitution {
        substitute(module("io.github.pasichdev:sealite")).using(project(":sealite"))
    }
}

rootProject.name = "sealite-sample"

include(":shared", ":androidApp", ":desktopApp")
