// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

/**
 * Android loads native code only from the app's own `lib/<abi>/` directory inside the installed
 * APK (or its extracted copy): the library cannot be replaced by a file in writable storage, so
 * no checksum step is needed.
 */
internal actual object NativeLibraryLoader {
    actual fun load() {
        System.loadLibrary("sealite_jni")
    }
}
