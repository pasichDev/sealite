// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlin.test.Test
import kotlin.test.assertEquals

/** The `static` entry points Java callers use exist on the classes themselves, not only on `Companion`. */
class JavaStaticEntryPointsTest {
    @Test
    fun configBuilderIsAStaticMethod() {
        val builder = SealiteConfig::class.java.getMethod("builder").invoke(null)
        assertEquals(SealiteConfig.DEFAULT, (builder as SealiteConfig.Builder).build())
    }
}
