// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * `sealite.cross-natives`: static OpenSSL plus the SQLCipher JNI library for the seven
 * non-Apple targets, cross-compiled from the build host. Apply it after `sealite.native` (it
 * reuses its `fetchSqlCipher` and `generateRenameHeader` tasks).
 *
 * Tasks: `fetchOpenSsl`, `buildOpenSsl<Target>`, `buildCrossNative<Target>`,
 * `verifyCrossExports<Target>` and the aggregate `buildAllCrossNatives`.
 * Outputs: `build/openssl/<target>/` and `build/native/cross/<target>/`.
 */
class SealiteCrossPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        check(tasks.names.contains("fetchSqlCipher") && tasks.names.contains("generateRenameHeader")) {
            "sealite.cross-natives needs sealite.native (fetchSqlCipher, generateRenameHeader): apply it first."
        }
        val osslVersion = "3.5.9"
        val osslSha256 = "603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
        // Primary key of "OpenSSL <openssl@openssl.org>"; it signs the 3.x release tarballs.
        val osslSigningKey = "B146647E45A7B33947AB226B2A2C87D161692D40"

        val fetchSqlCipher = tasks.named("fetchSqlCipher", FetchSqlCipherTask::class.java)
        val renameHeaderTask = tasks.named("generateRenameHeader", GenerateRenameHeaderTask::class.java)
        val nativeBuild = layout.buildDirectory.dir("native")
        val rootProjectDir = rootProject.layout.projectDirectory

        val fetchOpenSsl =
            tasks.register("fetchOpenSsl", FetchOpenSslTask::class.java) {
                group = "native"
                description = "Downloads OpenSSL $osslVersion, verifies sha256 and PGP signature, extracts it."
                version.set(osslVersion)
                sha256.set(osslSha256)
                signingKeyFingerprint.set(osslSigningKey)
                // CI must verify the signature; a developer machine without gpg only checks the pinned sha256.
                requirePgp.set(
                    providers.environmentVariable("CI").map { it.equals("true", ignoreCase = true) }.orElse(false),
                )
                downloadDir.set(rootProject.layout.buildDirectory.dir("native-downloads"))
                sourceDir.set(layout.buildDirectory.dir("openssl-src"))
                report.set(layout.buildDirectory.file("openssl-src-verification.txt"))
            }

        val aggregate =
            tasks.register("buildAllCrossNatives") {
                group = "native"
                description =
                    "Builds and verifies the JNI libraries for Windows x64, Linux x64/arm64 and the four Android ABIs."
            }
        val flags = NativeFlags.amalgamation("SQLCIPHER_CRYPTO_OPENSSL")

        for (t in CrossTarget.values()) {
            val fingerprint = providers.provider { CrossToolchains.resolve(t, rootProjectDir.asFile).fingerprint }

            val openssl: TaskProvider<BuildOpenSslTask> =
                tasks.register("buildOpenSsl${t.taskSuffix}", BuildOpenSslTask::class.java) {
                    group = "native"
                    description = "Builds the static OpenSSL libcrypto.a for ${t.dirName}."
                    target.set(t.name)
                    opensslVersion.set(osslVersion)
                    opensslSha256.set(osslSha256)
                    toolchainFingerprint.set(fingerprint)
                    configureOptions.set(
                        // no-autoload-config / no-module / no-dso: the static libcrypto never reads
                        // openssl.cnf from disk and never dlopen()s a module or engine.
                        listOf(
                            "no-shared",
                            "no-tests",
                            "no-apps",
                            "no-docs",
                            "no-autoload-config",
                            "no-module",
                            "no-dso",
                        ),
                    )
                    sourceDir.set(fetchOpenSsl.flatMap { it.sourceDir })
                    // sourceDir is @Internal (not an input), so Gradle does not infer this dependency.
                    dependsOn(fetchOpenSsl)
                    rootDir.set(rootProjectDir)
                    workDir.set(layout.buildDirectory.dir("openssl-work/${t.dirName}"))
                    outputDir.set(layout.buildDirectory.dir("openssl/${t.dirName}"))
                }

            val jni =
                tasks.register("buildCrossNative${t.taskSuffix}", BuildCrossJniLibraryTask::class.java) {
                    group = "native"
                    description = "Builds ${t.libName} for ${t.dirName}."
                    target.set(t.name)
                    toolchainFingerprint.set(fingerprint)
                    amalgamationFlags.set(flags)
                    amalgamationDir.set(fetchSqlCipher.flatMap { it.amalgamationDir })
                    renameHeader.set(renameHeaderTask.flatMap { it.header })
                    glueDir.set(layout.projectDirectory.dir("src/native/jni"))
                    opensslDir.set(openssl.flatMap { it.outputDir })
                    jdkHome.set(System.getProperty("java.home"))
                    rootDir.set(rootProjectDir)
                    workDir.set(nativeBuild.map { it.dir("cross-work/${t.dirName}") })
                    outputDir.set(nativeBuild.map { it.dir("cross/${t.dirName}") })
                }

            val verify =
                tasks.register("verifyCrossExports${t.taskSuffix}", VerifyCrossExportsTask::class.java) {
                    group = "verification"
                    description =
                        "Fails if ${t.dirName}'s library exports more than the JNI entry points or lacks OpenSSL."
                    target.set(t.name)
                    requiredString.set("OpenSSL $osslVersion")
                    library.set(jni.flatMap { it.outputDir.file(t.libName) })
                    rootDir.set(rootProjectDir)
                    report.set(nativeBuild.map { it.file("cross/${t.dirName}-exports.txt") })
                }
            aggregate.configure { dependsOn(verify) }
        }
    }
}
