// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

/*
 * A test that blocks inside native code can not be cancelled, and runTest's own timeout only fires
 * once that call returns, so a stall leaves no trace of where it is. The watchdog runs on a thread of
 * its own and, while the block is still running after [afterMillis], writes what every thread is
 * doing (and, on Android, what the kernel says about each one) to the log. It never interrupts the
 * test.
 */

/** Stops the watchdog started by [startStallWatchdog]. */
internal fun interface StallWatchdog {
    fun stop()
}

/** Starts a daemon thread that reports every [afterMillis] (at most three times) until stopped. */
internal expect fun startStallWatchdog(label: String, afterMillis: Long): StallWatchdog

internal inline fun <T> withStallReport(
    label: String,
    afterMillis: Long = STALL_REPORT_AFTER_MILLIS,
    block: () -> T,
): T {
    val watchdog = startStallWatchdog(label, afterMillis)
    try {
        return block()
    } finally {
        watchdog.stop()
    }
}

internal const val STALL_REPORT_AFTER_MILLIS = 45_000L
