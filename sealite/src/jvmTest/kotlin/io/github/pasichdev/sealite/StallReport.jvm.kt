// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

internal actual fun startStallWatchdog(label: String, afterMillis: Long): StallWatchdog {
    val lock = Object()
    var stopped = false
    val thread =
        Thread {
            repeat(MAX_REPORTS) { round ->
                synchronized(lock) {
                    val deadline = System.currentTimeMillis() + afterMillis
                    while (!stopped &&
                        System.currentTimeMillis() < deadline
                    ) {
                        lock.wait(deadline - System.currentTimeMillis())
                    }
                    if (stopped) return@Thread
                }
                System.err.println("SEALITE STALL $label: still running after ${(round + 1) * afterMillis} ms")
                for ((thread, frames) in Thread.getAllStackTraces()) {
                    System.err.println("\"${thread.name}\" ${thread.state}")
                    frames.forEach { System.err.println("    at $it") }
                }
            }
        }
    thread.isDaemon = true
    thread.name = "stall-watchdog"
    thread.start()
    return StallWatchdog {
        synchronized(lock) {
            stopped = true
            lock.notifyAll()
        }
    }
}

private const val MAX_REPORTS = 3
