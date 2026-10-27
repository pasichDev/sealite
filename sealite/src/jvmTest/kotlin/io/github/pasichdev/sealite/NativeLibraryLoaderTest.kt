// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.LoaderReflection.call
import io.github.pasichdev.sealite.LoaderReflection.freshLoader
import io.github.pasichdev.sealite.LoaderReflection.withProperties
import io.github.pasichdev.sealite.internal.NativeLibraryLoader
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the JVM loader finds the library, and what it refuses. */
class NativeLibraryLoaderTest {
    private val cleanUp = mutableListOf<File>()

    @AfterTest
    fun removeTemporaryFiles() {
        cleanUp.forEach { it.deleteRecursively() }
        cleanUp.clear()
    }

    private val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    private val ownerOnly = PosixFilePermissions.fromString("rwx------")
    private val mac = "os.name" to "Mac OS X"

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile.also {
        cleanUp += it
    }

    /** The cache directory the loader uses under a given home, on a pretend macOS. */
    private fun cacheDirIn(home: File): File {
        val arch = call<String>("getArchSuffix")
        return File(home, "Library/Caches/sealite/${BuildInfo.VERSION}/osx_$arch")
    }

    /** The extracted library of this platform: the very file every other test has loaded. */
    private fun realLibrary(): File {
        val dir = "natives/${call<String>("getOsPrefix")}_${call<String>("getArchSuffix")}"
        return call("extractVerified", dir, call<String>("libraryFileName"))
    }

    private fun extractFixture(dir: String): File = call("extractVerified", "natives/$dir", "libfixture.so")

    // ---- where load() looks ------------------------------------------------------------------

    @Test
    fun theExplicitPathIsLoadedFirst() = freshLoader {
        val library = realLibrary()
        withProperties("sealite.library.path" to library.path, "compose.application.resources.dir" to "/nonexistent") {
            NativeLibraryLoader.load()
        }
        assertTrue(LoaderReflection.loaded)
    }

    @Test
    fun theExplicitPathMustBeAnExistingAbsoluteFile() = freshLoader {
        val dir = tempDir("sealite-explicit")
        for (bad in listOf("relative/libsealite_jni.so", File(dir, "missing.so").path, dir.path)) {
            val e =
                assertFailsWith<IllegalStateException>(bad) {
                    withProperties("sealite.library.path" to bad) { NativeLibraryLoader.load() }
                }
            assertTrue(e.message.orEmpty().contains("sealite.library.path"), e.message)
        }
        assertTrue(!LoaderReflection.loaded)
    }

    @Test
    fun aBlankExplicitPathIsIgnored() = freshLoader {
        withProperties("sealite.library.path" to "  ", "compose.application.resources.dir" to null) {
            NativeLibraryLoader.load()
        }
        assertTrue(LoaderReflection.loaded)
    }

    @Test
    fun theComposeResourcesDirectoryComesSecond() = freshLoader {
        val library = realLibrary()
        withProperties(
            "sealite.library.path" to null,
            "compose.application.resources.dir" to library.parentFile.path,
        ) {
            NativeLibraryLoader.load()
        }
        assertTrue(LoaderReflection.loaded)
    }

    @Test
    fun aComposeDirectoryWithoutTheLibraryFallsThroughToTheJar() {
        val empty = tempDir("sealite-compose")
        for (dir in listOf(empty.path, "relative/dir", " ")) {
            freshLoader {
                withProperties("sealite.library.path" to null, "compose.application.resources.dir" to dir) {
                    NativeLibraryLoader.load()
                }
                assertTrue(LoaderReflection.loaded, dir)
            }
        }
    }

    @Test
    fun withoutAnyPropertyTheJarCopyIsExtractedVerifiedAndLoaded() = freshLoader {
        withProperties("sealite.library.path" to null, "compose.application.resources.dir" to null) {
            NativeLibraryLoader.load()
            NativeLibraryLoader.load() // already loaded: returns at once
        }
        assertTrue(LoaderReflection.loaded)
    }

    @Test
    fun anUnknownSystemIsRefusedBeforeAnythingIsLoaded() = freshLoader {
        for (name in listOf("Plan 9", null)) {
            val e =
                assertFailsWith<IllegalStateException>("$name") {
                    withProperties("os.name" to name) { NativeLibraryLoader.load() }
                }
            assertTrue(
                e.message.orEmpty().contains("Unsupported operating system") ||
                    e.message.orEmpty().contains("Cannot read os.name"),
                e.message,
            )
        }
        assertTrue(!LoaderReflection.loaded)
    }

    // ---- platform names ----------------------------------------------------------------------

    @Test
    fun operatingSystemsMapToTheirResourcePrefix() {
        val names =
            mapOf(
                "Linux" to "linux",
                "Mac OS X" to "osx",
                "osx" to "osx",
                "Windows 11" to "windows",
            )
        for ((name, prefix) in names) {
            assertEquals(prefix, withProperties("os.name" to name) { call<String>("getOsPrefix") }, name)
        }
        assertEquals(
            "Unsupported operating system: plan 9",
            assertFailsWith<IllegalStateException> {
                withProperties("os.name" to "Plan 9") { call<String>("getOsPrefix") }
            }.message,
        )
        assertEquals(
            "Cannot read os.name",
            assertFailsWith<IllegalStateException> {
                withProperties("os.name" to null) { call<String>("getOsName") }
            }.message,
        )
    }

    @Test
    fun architecturesMapToTheirResourceSuffix() {
        val archs =
            mapOf(
                "aarch64" to "arm64",
                "arm64-v8a" to "arm64",
                "arm" to "arm32",
                "amd64" to "x64",
                "x86_64" to "x64",
                "x86" to "x86",
                "i386" to "x86",
            )
        for ((arch, suffix) in archs) {
            assertEquals(suffix, withProperties("os.arch" to arch) { call<String>("getArchSuffix") }, arch)
        }
        assertEquals(
            "Unsupported architecture: riscv",
            assertFailsWith<IllegalStateException> {
                withProperties("os.arch" to "riscv") { call<String>("getArchSuffix") }
            }.message,
        )
        assertEquals(
            "Cannot read os.arch",
            assertFailsWith<IllegalStateException> {
                withProperties("os.arch" to null) { call<String>("getOsArch") }
            }.message,
        )
    }

    @Test
    fun theLibraryFileNameFollowsTheOperatingSystem() {
        val names =
            mapOf(
                "Linux" to "libsealite_jni.so",
                "Mac OS X" to "libsealite_jni.dylib",
                "Windows 10" to "sealite_jni.dll",
            )
        for ((os, file) in names) {
            assertEquals(file, withProperties("os.name" to os) { call<String>("libraryFileName") }, os)
        }
    }

    @Test
    fun withoutAHomeDirectoryThereIsNowhereToCache() {
        val e =
            assertFailsWith<IllegalStateException> { withProperties("user.home" to null) { call<File>("cacheRoot") } }
        assertEquals("Cannot read user.home", e.message)
        // jvmTest runs without XDG_CACHE_HOME and LOCALAPPDATA; LoaderEnvironmentTest covers them set.
        val roots =
            mapOf(
                "Mac OS X" to "/h/Library/Caches/sealite",
                "Windows 11" to "/h/AppData/Local/sealite",
                "Linux" to "/h/.cache/sealite",
            )
        for ((os, root) in roots) {
            assertEquals(
                File(root),
                withProperties("os.name" to os, "user.home" to "/h") { call<File>("cacheRoot") },
                os,
            )
        }
    }

    // ---- the private cache directory -------------------------------------------------------------

    @Test
    fun anOwnedDirectoryIsUsedAndTightenedToOwnerOnly() {
        if (!posix) return
        val home = tempDir("sealite-home")
        val dir = cacheDirIn(home).also { it.mkdirs() }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
        val used = withProperties("user.home" to home.path, mac) { call<File?>("privateCacheDir") }
        assertEquals(dir, used)
        assertEquals(ownerOnly, Files.getPosixFilePermissions(dir.toPath()))
    }

    @Test
    fun aMissingCacheDirectoryIsCreated() {
        if (!posix) return
        val home = tempDir("sealite-home")
        val used = withProperties("user.home" to home.path, mac) { call<File?>("privateCacheDir") }
        assertEquals(cacheDirIn(home), used)
        assertEquals(ownerOnly, Files.getPosixFilePermissions(cacheDirIn(home).toPath()))
    }

    @Test
    fun aSymlinkInPlaceOfTheCacheDirectoryIsNotTrusted() {
        if (!posix) return
        val home = tempDir("sealite-home")
        val elsewhere = tempDir("sealite-elsewhere")
        val dir = cacheDirIn(home)
        dir.parentFile.mkdirs()
        Files.createSymbolicLink(dir.toPath(), elsewhere.toPath())
        assertNull(withProperties("user.home" to home.path, mac) { call<File?>("privateCacheDir") })
    }

    @Test
    fun aFileInPlaceOfTheCacheDirectoryIsNotTrusted() {
        val home = tempDir("sealite-home")
        val dir = cacheDirIn(home)
        dir.parentFile.mkdirs()
        dir.writeText("not a directory")
        assertNull(withProperties("user.home" to home.path, mac) { call<File?>("privateCacheDir") })
    }

    @Test
    fun aCacheDirectoryOwnedBySomeoneElseIsNotTrusted() {
        if (!posix) return
        val home = tempDir("sealite-home")
        cacheDirIn(home).mkdirs()
        val used =
            withProperties("user.home" to home.path, "user.name" to "somebody-else", mac) {
                call<File?>("privateCacheDir")
            }
        assertNull(used)
    }

    // ---- extraction ------------------------------------------------------------------------------

    @Test
    fun extractionVerifiesReusesAndRepairsTheCachedCopy() {
        val home = tempDir("sealite-home")
        val payload = "fixture payload\n".toByteArray()
        withProperties("user.home" to home.path, mac) {
            val first = extractFixture("fixture_good")
            assertEquals(cacheDirIn(home), first.parentFile)
            assertContentEquals(payload, first.readBytes())

            // A cached copy that matches is reused as it is.
            assertTrue(first.setLastModified(1_000_000_000_000L))
            assertEquals(first, extractFixture("fixture_good"))
            assertEquals(1_000_000_000_000L, first.lastModified())

            // One that does not is replaced.
            first.writeText("tampered")
            assertEquals(first, extractFixture("fixture_good"))
            assertContentEquals(payload, first.readBytes())
        }
    }

    @Test
    fun aPlantedSymlinkInTheCacheIsRemovedNotFollowed() {
        if (!posix) return
        val home = tempDir("sealite-home")
        val victim = File(tempDir("sealite-victim"), "victim.so").apply { writeText("victim") }
        withProperties("user.home" to home.path, mac) {
            val target = File(cacheDirIn(home).also { it.mkdirs() }, "libfixture.so")
            Files.createSymbolicLink(target.toPath(), victim.toPath())
            val extracted = extractFixture("fixture_good")
            assertTrue(!Files.isSymbolicLink(extracted.toPath()))
            assertContentEquals("fixture payload\n".toByteArray(), extracted.readBytes())
            assertEquals("victim", victim.readText(), "what the symlink pointed at must be untouched")
        }
    }

    @Test
    fun anUnsafeCacheDirectoryMakesTheLoaderExtractToAFreshPrivateOne() {
        if (!posix) return
        val home = tempDir("sealite-home")
        val dir = cacheDirIn(home)
        dir.parentFile.mkdirs()
        Files.createSymbolicLink(dir.toPath(), tempDir("sealite-elsewhere").toPath())
        withProperties("user.home" to home.path, mac) {
            val extracted = extractFixture("fixture_good")
            cleanUp += extracted.parentFile
            assertNotEquals(dir, extracted.parentFile)
            assertTrue(extracted.parentFile.name.startsWith("sealite-"), extracted.parentFile.name)
            assertEquals(ownerOnly, Files.getPosixFilePermissions(extracted.parentFile.toPath()))
            assertContentEquals("fixture payload\n".toByteArray(), extracted.readBytes())
        }
    }

    @Test
    fun aCopyThatDoesNotMatchItsChecksumIsRefusedAndLeavesNothingBehind() {
        val home = tempDir("sealite-home")
        withProperties("user.home" to home.path, mac) {
            val e = assertFailsWith<IOException> { extractFixture("fixture_mismatch") }
            assertTrue(e.message.orEmpty().startsWith("Checksum mismatch for libfixture.so"), e.message)
            assertEquals(emptyList(), cacheDirIn(home).list()?.toList(), "no .part file and no library")
        }
    }

    @Test
    fun aMalformedChecksumFileIsRefused() {
        val e = assertFailsWith<IllegalStateException> { extractFixture("fixture_malformed") }
        assertEquals("Malformed checksum file for libfixture.so", e.message)
    }

    @Test
    fun aMissingChecksumOrLibraryIsReportedAsNotBundled() {
        val home = tempDir("sealite-home")
        withProperties("user.home" to home.path, mac) {
            for (dir in listOf("fixture_absent", "fixture_nolib")) {
                val e = assertFailsWith<IllegalStateException>(dir) { extractFixture(dir) }
                assertTrue(e.message.orEmpty().startsWith("No bundled Sealite native library for "), e.message)
            }
            assertEquals(emptyList(), cacheDirIn(home).list()?.toList(), "the temporary file is removed")
        }
    }
}
