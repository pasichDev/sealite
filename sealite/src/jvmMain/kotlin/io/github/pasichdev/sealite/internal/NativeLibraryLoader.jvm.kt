/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modified for Sealite: ported from NativeLibraryLoader.jvm.kt. The library is never looked up on
 * java.library.path; it comes from an app-chosen path, the Compose Desktop resources dir (where a
 * packaged app keeps its signed copy), or the jar, extracted to a per-version cache directory and
 * checked against a build-time sha256 before System.load. The cache
 * directory must be a real directory owned by the current user with mode 0700 (POSIX); otherwise the
 * library is extracted to a fresh private directory of its own.
 */
package io.github.pasichdev.sealite.internal

import io.github.pasichdev.sealite.BuildInfo
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale

internal actual object NativeLibraryLoader {
    private const val LIB_NAME = "sealite_jni"

    private var loaded = false

    private val osName: String
        get() = System.getProperty("os.name")?.lowercase(Locale.US) ?: error("Cannot read os.name")

    private val osArch: String
        get() = System.getProperty("os.arch")?.lowercase(Locale.US) ?: error("Cannot read os.arch")

    private val osPrefix: String
        get() =
            when {
                osName.contains("linux") -> "linux"
                osName.contains("mac") || osName.contains("osx") -> "osx"
                osName.contains("windows") -> "windows"
                else -> error("Unsupported operating system: $osName")
            }

    private val archSuffix: String
        get() =
            when {
                osArch == "aarch64" -> "arm64"
                osArch.contains("arm") -> if (osArch.contains("64")) "arm64" else "arm32"
                osArch.contains("64") -> "x64"
                osArch.contains("86") -> "x86"
                else -> error("Unsupported architecture: $osArch")
            }

    /**
     * Loads the library from, in order:
     * 1. the file named by the `sealite.library.path` system property (the app chose it);
     * 2. the Compose Desktop app-resources dir (`compose.application.resources.dir`), where a
     *    packaged, notarized app keeps its signed copy;
     * 3. the copy inside this jar, extracted to an owner-only cache dir (or, when that directory is
     *    not safe, to a fresh private one) and checked against the sha256 recorded at build time.
     *
     * `System.loadLibrary` is deliberately not used: `java.library.path` includes `PATH` and, on
     * Windows, the working directory, so a planted `sealite_jni.dll` there would be loaded
     * unchecked.
     */
    actual fun load(): Unit = synchronized(this) {
        if (loaded) return
        val libName = libraryFileName()
        val library =
            explicitLibrary()
                ?: composeResourcesLibrary(libName)
                ?: extractVerified("natives/${osPrefix}_$archSuffix", libName)
        @Suppress("UnsafeDynamicallyLoadedCode")
        System.load(library.canonicalPath)
        loaded = true
    }

    private fun explicitLibrary(): File? {
        val path = System.getProperty(LIBRARY_PATH_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        check(file.isAbsolute && file.isFile) { "$LIBRARY_PATH_PROPERTY must name an existing absolute file: $path" }
        return file
    }

    private fun composeResourcesLibrary(libName: String): File? {
        val dir = System.getProperty(COMPOSE_RESOURCES_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
        return File(dir, libName).takeIf { it.isAbsolute && it.isFile }
    }

    private const val LIBRARY_PATH_PROPERTY = "sealite.library.path"
    private const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"

    private fun libraryFileName(): String = when (osPrefix) {
        "linux" -> "lib$LIB_NAME.so"
        "osx" -> "lib$LIB_NAME.dylib"
        else -> "$LIB_NAME.dll"
    }

    /**
     * Copies `<resourceDir>/<libName>` to the cache and returns a file whose sha256 equals the one
     * recorded at build time in `<libName>.sha256`. A cached copy is reused only if it matches;
     * otherwise it is replaced through a temporary file and an atomic move.
     */
    private fun extractVerified(resourceDir: String, libName: String): File {
        val loader = NativeLibraryLoader::class.java.classLoader!!
        val expected =
            loader.getResourceAsStream("$resourceDir/$libName.sha256").use { stream ->
                checkNotNull(stream) { "No bundled Sealite native library for $osName | $osArch" }
                    .readBytes()
                    .decodeToString()
                    .trim()
                    .lowercase(Locale.US)
            }
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "Malformed checksum file for $libName" }

        val dir = privateCacheDir() ?: freshPrivateDir()
        val target = File(dir, libName)
        // A planted symlink must not redirect the hash check or the load.
        if (Files.isSymbolicLink(target.toPath())) Files.delete(target.toPath())
        if (target.isFile && sha256(target.canonicalFile) == expected) return target.canonicalFile

        val temp = Files.createTempFile(dir.toPath(), "sealite_", ".part")
        try {
            loader.getResourceAsStream("$resourceDir/$libName").use { stream ->
                checkNotNull(stream) { "No bundled Sealite native library for $osName | $osArch" }
                Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING)
            }
            val actual = sha256(temp.toFile())
            if (actual != expected) {
                throw IOException("Checksum mismatch for $libName: expected $expected, got $actual")
            }
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
        // Verify again what is on disk now, right before it is loaded, and hand back the canonical
        // path that was hashed: the caller loads exactly this file.
        val canonical = target.canonicalFile
        check(canonical.parentFile == dir.canonicalFile) { "$libName resolves outside $dir" }
        check(sha256(canonical) == expected) { "Cached $libName changed after extraction" }
        return canonical
    }

    private val isPosix: Boolean
        get() = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    /**
     * The per-version cache directory, created if needed, or null when it can not be trusted: not a
     * real directory, not owned by the current user, or readable / writable by anyone else (POSIX).
     * Where a directory is ours but too open it is tightened first; ownership can not be repaired.
     */
    private fun privateCacheDir(): File? {
        val dir = cacheRoot().resolve(BuildInfo.VERSION).resolve("${osPrefix}_$archSuffix")
        return try {
            dir.mkdirs()
            val path = dir.toPath()
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
            if (isPosix) {
                if (!isOwnedByMe(path)) return null
                restrictToOwner(dir)
                val permissions =
                    Files
                        .readAttributes(
                            path,
                            PosixFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        ).permissions()
                if (permissions != PosixFilePermissions.fromString("rwx------")) return null
            }
            dir
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** A new directory only this process and user can use, for when the cache directory is unsafe. */
    private fun freshPrivateDir(): File {
        val dir =
            if (isPosix) {
                Files.createTempDirectory(
                    "sealite-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
            } else {
                Files.createTempDirectory("sealite-")
            }
        dir.toFile().deleteOnExit()
        return dir.toFile()
    }

    private fun isOwnedByMe(path: java.nio.file.Path): Boolean {
        val owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name
        return owner == System.getProperty("user.name")
    }

    private fun cacheRoot(): File {
        val home = System.getProperty("user.home") ?: error("Cannot read user.home")
        val base =
            when (osPrefix) {
                "osx" -> File(home, "Library/Caches")
                "windows" -> System.getenv("LOCALAPPDATA")?.let(::File) ?: File(home, "AppData/Local")
                else -> System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".cache")
            }
        return File(base, "sealite")
    }

    private fun restrictToOwner(dir: File) {
        // Best effort: owner-only on POSIX file systems, a no-op elsewhere.
        try {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        } catch (_: UnsupportedOperationException) {
        } catch (_: IOException) {
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
