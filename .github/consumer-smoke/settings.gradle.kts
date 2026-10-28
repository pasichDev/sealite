// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

// A consumer that knows nothing about this repository: it depends on the released coordinates only, and
// io.github.pasichdev comes from the staged Maven repository and from nowhere else. Run by release.yml:
//   ./gradlew -p .github/consumer-smoke test -Psealite.staging=<dir> -Psealite.version=<x.y.z> -Pkotlin.version=<k>

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlin.version").get()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "Staging"
                    url = uri(providers.gradleProperty("sealite.staging").get())
                }
            }
            filter { includeGroup("io.github.pasichdev") }
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "sealite-consumer-smoke"
