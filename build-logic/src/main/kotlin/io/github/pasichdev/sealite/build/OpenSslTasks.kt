// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import javax.inject.Inject

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Runs [cmd] with its output in [log]; on failure shows the log's tail and throws. */
internal fun ExecOperations.runLogged(
    cmd: List<String>,
    dir: File,
    env: Map<String, String>,
    log: File,
    what: String,
) {
    log.parentFile.mkdirs()
    val result =
        log.outputStream().use { out ->
            exec {
                commandLine(cmd)
                workingDir(dir)
                environment(env)
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
        }
    if (result.exitValue != 0) {
        val tail = log.readLines().takeLast(40).joinToString("\n")
        throw GradleException("$what failed (exit ${result.exitValue}), log: $log\n$tail")
    }
}

private fun download(url: String, to: File) {
    val tmp = File(to.path + ".part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    Files.move(tmp.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}

/**
 * Downloads an OpenSSL release tarball from github.com/openssl/openssl/releases, checks the pinned
 * sha256 and (when `gpg` is installed) the release's detached PGP signature against a pinned
 * signing-key fingerprint, then extracts it into [sourceDir]. [report] says what was verified.
 */
abstract class FetchOpenSslTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** `3.5.9`. */
        @get:Input abstract val version: Property<String>

        @get:Input abstract val sha256: Property<String>

        /** Fingerprint of the primary key that signs OpenSSL releases (the signing subkey belongs to it). */
        @get:Input abstract val signingKeyFingerprint: Property<String>

        /** Fail instead of skipping the PGP check when `gpg` is missing. */
        @get:Input abstract val requirePgp: Property<Boolean>

        /** Where tarball, signature and key are kept (survives `clean`). */
        @get:Internal abstract val downloadDir: DirectoryProperty

        @get:OutputDirectory abstract val sourceDir: DirectoryProperty

        @get:OutputFile abstract val report: RegularFileProperty

        @TaskAction
        fun run() {
            val v = version.get()
            val base = "https://github.com/openssl/openssl/releases/download/openssl-$v/openssl-$v.tar.gz"
            val dl = downloadDir.get().asFile.also { it.mkdirs() }
            val tarball = dl.resolve("openssl-$v.tar.gz")
            val signature = dl.resolve("openssl-$v.tar.gz.asc")

            if (!tarball.isFile || sha256Hex(tarball) != sha256.get()) {
                logger.lifecycle("Downloading $base")
                download(base, tarball)
            }
            val actual = sha256Hex(tarball)
            if (actual != sha256.get()) {
                tarball.delete()
                throw GradleException("sha256 mismatch for $base: expected ${sha256.get()}, got $actual")
            }
            val lines = mutableListOf("openssl-$v.tar.gz sha256 $actual: OK (matches the pinned value)")
            lines += verifyPgp(base, dl, tarball, signature)
            report
                .get()
                .asFile
                .also { it.parentFile.mkdirs() }
                .writeText(lines.joinToString("\n") + "\n")
            lines.forEach { logger.lifecycle(it) }

            val src = sourceDir.get().asFile
            src.deleteRecursively()
            src.mkdirs()
            exec.exec {
                commandLine(
                    "tar",
                    "-xzf",
                    tarball.absolutePath,
                    "-C",
                    src.absolutePath,
                    "--strip-components=1",
                )
            }
        }

        private fun gpgAvailable(): Boolean = try {
            ProcessBuilder(
                "gpg",
                "--version",
            ).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() ==
                0
        } catch (_: java.io.IOException) {
            false
        }

        private fun verifyPgp(base: String, dl: File, tarball: File, signature: File): List<String> {
            if (!gpgAvailable()) {
                if (requirePgp.get()) {
                    throw GradleException(
                        "gpg is not installed and requirePgp is set: cannot verify the OpenSSL signature",
                    )
                }
                return listOf("PGP: SKIPPED, gpg is not installed (only the pinned sha256 was verified)")
            }
            val fingerprint = signingKeyFingerprint.get()
            val key = dl.resolve("openssl-signing-$fingerprint.asc")
            if (!signature.isFile) download("$base.asc", signature)
            if (!key.isFile) download("https://keys.openpgp.org/vks/v1/by-fingerprint/$fingerprint", key)

            // A private keyring, so nothing leaks into (or is trusted from) the user's own.
            val home = Files.createTempDirectory("sealite-gpg").toFile()
            try {
                Files.setPosixFilePermissions(
                    home.toPath(),
                    java.nio.file.attribute.PosixFilePermissions
                        .fromString("rwx------"),
                )
                val gpg = listOf("gpg", "--batch", "--no-tty", "--homedir", home.path)
                exec.runLogged(
                    gpg + listOf("--import", key.path),
                    dl,
                    emptyMap(),
                    home.resolve("import.log"),
                    "gpg --import",
                )
                val status = home.resolve("verify.log")
                val result =
                    status.outputStream().use { out ->
                        exec.exec {
                            commandLine(gpg + listOf("--status-fd", "1", "--verify", signature.path, tarball.path))
                            standardOutput = out
                            errorOutput = out
                            isIgnoreExitValue = true
                        }
                    }
                val valid = status.readLines().firstOrNull { it.startsWith("[GNUPG:] VALIDSIG") }
                // VALIDSIG <signing (sub)key fpr> <date> <ts> ... <primary key fpr>
                if (result.exitValue != 0 || valid == null || valid.trim().split(' ').last() != fingerprint) {
                    throw GradleException(
                        "OpenSSL PGP signature check failed (expected primary key $fingerprint):\n${status.readText()}",
                    )
                }
                return listOf("PGP: good signature, primary key $fingerprint (OpenSSL <openssl@openssl.org>)")
            } finally {
                runCatching {
                    ProcessBuilder("gpgconf", "--homedir", home.path, "--kill", "all")
                        .redirectErrorStream(true)
                        .start()
                        .also { it.inputStream.readBytes() }
                        .waitFor()
                }
                home.deleteRecursively()
            }
        }
    }

/**
 * Builds a static `libcrypto.a` (no libssl, no shared, no apps / tests / docs) for one cross target
 * out of tree and publishes `lib/libcrypto.a`, the `openssl` headers under `include/` and `LICENSE.txt` into
 * [outputDir]. The build tree is deleted afterwards. Up to date as long as the version, the
 * toolchain fingerprint and the Configure options are unchanged.
 */
abstract class BuildOpenSslTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Input abstract val target: Property<String>

        @get:Input abstract val opensslVersion: Property<String>

        @get:Input abstract val opensslSha256: Property<String>

        @get:Input abstract val toolchainFingerprint: Property<String>

        @get:Input abstract val configureOptions: ListProperty<String>

        /** The extracted release (from [FetchOpenSslTask]). Not an input: the version and sha256 are. */
        @get:Internal abstract val sourceDir: DirectoryProperty

        @get:Internal abstract val rootDir: DirectoryProperty

        @get:Internal abstract val workDir: DirectoryProperty

        @get:OutputDirectory abstract val outputDir: DirectoryProperty

        @TaskAction
        fun run() {
            val started = System.nanoTime()
            val t = CrossTarget.valueOf(target.get())
            val tc = CrossToolchains.resolve(t, rootDir.get().asFile)
            val src = sourceDir.get().asFile
            val work =
                workDir.get().asFile.also {
                    it.deleteRecursively()
                    it.mkdirs()
                }

            // llvm-ar doubles as llvm-ranlib when invoked under that name.
            val tools = work.resolve("tools").also { it.mkdirs() }
            if (t.jni != JniKind.ANDROID) {
                Files.createSymbolicLink(tools.resolve("llvm-ar").toPath(), tc.ar.toPath())
                Files.createSymbolicLink(tools.resolve("llvm-ranlib").toPath(), tc.ar.toPath())
            }
            val path =
                (listOf(tools) + tc.opensslPath).joinToString(File.pathSeparator) { it.path } + File.pathSeparator +
                    System.getenv("PATH")
            val env =
                buildMap {
                    putAll(tc.opensslEnv)
                    put("PATH", path)
                    if (t.jni != JniKind.ANDROID) {
                        put("CC", (listOf(tc.cc.path) + tc.compileArgs).joinToString(" "))
                        put("AR", "llvm-ar")
                        put("RANLIB", "llvm-ranlib")
                    }
                }

            val build = work.resolve("build").also { it.mkdirs() }
            exec.runLogged(
                listOf("perl", src.resolve("Configure").path, t.opensslTarget) + configureOptions.get() +
                    tc.opensslConfigureArgs,
                build,
                env,
                work.resolve("configure.log"),
                "OpenSSL Configure for ${t.opensslTarget}",
            )
            exec.runLogged(
                listOf("make", "-j${Runtime.getRuntime().availableProcessors()}", "build_libs"),
                build,
                env,
                work.resolve("make.log"),
                "OpenSSL make build_libs for ${t.opensslTarget}",
            )

            val out =
                outputDir.get().asFile.also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
            build.resolve("libcrypto.a").copyTo(out.resolve("lib/libcrypto.a"), overwrite = true)
            // Headers: the release's own plus the ones Configure generates (configuration.h, opensslv.h ...).
            for (headers in listOf(src.resolve("include/openssl"), build.resolve("include/openssl"))) {
                headers
                    .listFiles { f ->
                        f.extension == "h"
                    }.orEmpty()
                    .forEach { it.copyTo(out.resolve("include/openssl/${it.name}"), overwrite = true) }
            }
            src.resolve("LICENSE.txt").copyTo(out.resolve("LICENSE.txt"), overwrite = true)
            work.deleteRecursively()
            logger.lifecycle(
                "libcrypto.a for ${t.dirName}: ${out
                    .resolve(
                        "lib/libcrypto.a",
                    ).length() / 1024} KiB in ${(System.nanoTime() - started) / 1_000_000_000}s",
            )
        }
    }
