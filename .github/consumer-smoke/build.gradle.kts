// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val sealiteVersion = providers.gradleProperty("sealite.version").get()

dependencies {
    testImplementation("io.github.pasichdev:sealite:$sealiteVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("expected.version", sealiteVersion)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
