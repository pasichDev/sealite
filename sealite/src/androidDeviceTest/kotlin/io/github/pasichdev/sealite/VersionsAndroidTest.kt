// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionsAndroidTest {
    @Test
    fun androidLinksOpenSsl359() {
        val v = Sealite.versions
        assertEquals("openssl", v.cryptoProvider.lowercase(), v.toString())
        assertTrue(v.cryptoProviderVersion.contains("3.5.9"), "provider version was: ${v.cryptoProviderVersion}")
        assertTrue(v.sqlcipher.isNotEmpty())
    }
}
