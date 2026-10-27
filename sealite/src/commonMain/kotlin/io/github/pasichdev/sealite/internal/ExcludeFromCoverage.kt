// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.internal

/**
 * Marks code that no test can reach (an I/O fault between two steps, a condition SQLite never
 * produces). The Kover coverage report skips what carries it (see `sealite.coverage`). Every use
 * names the reason next to it; keep this to the few places that really are unreachable.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CONSTRUCTOR,
)
@Retention(AnnotationRetention.BINARY)
internal annotation class ExcludeFromCoverage
