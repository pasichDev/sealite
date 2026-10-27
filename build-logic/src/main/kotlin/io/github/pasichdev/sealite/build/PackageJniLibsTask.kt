// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Lays the Android JNI libraries out as `<outputDir>/<abi>/libsealite_jni.so`, the shape AGP
 * expects of a `jniLibs` directory. Only the `.so` files go in (never a `.sha256`), and every ABI
 * in [libraries] must be present: a missing library fails the build instead of shipping an AAR
 * that crashes on that ABI.
 */
abstract class PackageJniLibsTask
    @Inject
    constructor(
        private val fs: FileSystemOperations,
    ) : DefaultTask() {
        /** ABI name (`arm64-v8a`, ...) to the library built or prebuilt for it. */
        @get:Internal abstract val libraries: MapProperty<String, RegularFile>

        /** The same files as [libraries], declared as inputs. */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val libraryFiles: ConfigurableFileCollection

        @get:OutputDirectory abstract val outputDir: DirectoryProperty

        fun library(abi: String, file: Provider<RegularFile>) {
            libraries.put(abi, file)
            libraryFiles.from(file)
        }

        @TaskAction
        fun run() {
            val out = outputDir.get().asFile
            fs.delete { delete(out) }
            for ((abi, file) in libraries.get()) {
                val source = file.asFile
                if (!source.isFile) throw GradleException("No Android library for $abi: expected ${source.path}")
                fs.copy {
                    from(source)
                    into(out.resolve(abi))
                }
            }
        }
    }
