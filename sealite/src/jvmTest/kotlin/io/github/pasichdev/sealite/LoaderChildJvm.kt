// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import java.io.File

/**
 * The entry point of the JVM that [LoaderExitTest] starts: extracts the fixture library the way the
 * loader does and prints where it went, so the test can look at what is left once this JVM is gone.
 */
fun main() {
    val extracted = LoaderReflection.call<File>("extractVerified", "natives/fixture_good", "libfixture.so")
    println(extracted.path)
}
