// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.internal.NativeLibraryLoader
import java.lang.reflect.InvocationTargetException

/**
 * Access to the private steps of [NativeLibraryLoader] and to the system properties it reads. The
 * loader is a process-wide singleton that loads one library once, so its decisions are driven
 * through its own functions with the properties a test needs.
 */
internal object LoaderReflection {
    private val type = NativeLibraryLoader::class.java

    @Suppress("UNCHECKED_CAST")
    fun <T> call(name: String, vararg args: Any?): T {
        val method = type.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        try {
            return method.invoke(NativeLibraryLoader, *args) as T
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    var loaded: Boolean
        get() = flag().getBoolean(NativeLibraryLoader)
        set(value) = flag().setBoolean(NativeLibraryLoader, value)

    private fun flag() = type.getDeclaredField("loaded").also { it.isAccessible = true }

    /** Runs [block] with the properties set (null clears one), then puts every one back. */
    fun <T> withProperties(vararg properties: Pair<String, String?>, block: () -> T): T {
        val before = properties.associate { (key, _) -> key to System.getProperty(key) }
        try {
            for ((key, value) in properties) {
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            return block()
        } finally {
            for ((key, value) in before) {
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    /** Runs [block] as if the library had not been loaded yet, then restores the flag. */
    fun <T> freshLoader(block: () -> T): T {
        val before = loaded
        loaded = false
        try {
            return block()
        } finally {
            loaded = before
        }
    }
}
