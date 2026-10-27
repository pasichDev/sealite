// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sealite-root"

include(":sealite")
include(":internal:konan-sysroots")
