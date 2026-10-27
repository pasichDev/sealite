// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Writes `io/github/pasichdev/sealite/BuildInfo.kt` with the project version. */
@CacheableTask
abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:Input abstract val version: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val dir = outputDir.get().asFile.resolve("io/github/pasichdev/sealite")
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            """
            package io.github.pasichdev.sealite

            internal object BuildInfo {
                const val VERSION = "${version.get()}"
            }
            """.trimIndent() + "\n",
        )
    }
}
