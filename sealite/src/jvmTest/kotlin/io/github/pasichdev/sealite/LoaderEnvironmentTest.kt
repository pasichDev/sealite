// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.LoaderReflection.call
import io.github.pasichdev.sealite.LoaderReflection.withProperties
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Where the cache directory goes on Windows and elsewhere. That depends on environment variables a
 * test can not set from inside the JVM, so the build runs this class once per environment
 * (`jvmLoaderEnvTest*` tasks, see sealite/build.gradle.kts) and leaves it out of `jvmTest`; the
 * expectations read the variables the task gave this process.
 */
class LoaderEnvironmentTest {
    private val home = "/home/test"

    private fun cacheRoot(os: String): File = withProperties("os.name" to os, "user.home" to home) {
        call<File>("cacheRoot")
    }

    @Test
    fun macOsKeepsItsCachesUnderLibrary() {
        assertEquals(File(home, "Library/Caches/sealite"), cacheRoot("Mac OS X"))
    }

    @Test
    fun windowsUsesLocalAppDataWhenSetAndAppDataLocalOtherwise() {
        val localAppData = System.getenv("LOCALAPPDATA")
        val expected = if (localAppData != null) File(localAppData, "sealite") else File(home, "AppData/Local/sealite")
        assertEquals(expected, cacheRoot("Windows 11"))
    }

    @Test
    fun otherSystemsUseXdgCacheHomeWhenItIsNotBlankAndDotCacheOtherwise() {
        val xdg = System.getenv("XDG_CACHE_HOME")
        val expected =
            if (xdg != null && xdg.isNotBlank()) File(xdg, "sealite") else File(home, ".cache/sealite")
        assertEquals(expected, cacheRoot("Linux"))
    }
}
