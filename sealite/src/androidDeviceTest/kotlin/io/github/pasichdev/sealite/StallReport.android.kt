// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import android.util.Log
import java.io.File

/*
 * The report goes to logcat, which AGP saves per test next to the JUnit XML. Besides the Java
 * stacks it has, for every thread of the process, what the kernel knows: its state (R running,
 * D uninterruptible I/O wait, S sleeping), CPU time so far, the kernel function it sleeps in
 * (wchan) and the syscall it is in. Native frames are not available, but a thread in JNI shows up
 * here as R (computing) or D / a syscall (waiting on a file), which is what tells a livelock, a
 * lock wait and slow storage apart. Each report repeats the CPU times so that the next one shows
 * which threads advanced.
 */
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
                report(label, (round + 1) * afterMillis)
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

private fun report(label: String, elapsedMillis: Long) {
    val out = StringBuilder("$label: still running after $elapsedMillis ms\n")
    out.append("--- java threads\n")
    for ((thread, frames) in Thread.getAllStackTraces()) {
        if (thread.name.endsWith("Daemon") || thread.name == "stall-watchdog") continue
        out.append("\"${thread.name}\" tid=${thread.id} ${thread.state}\n")
        frames.forEach { out.append("    at $it\n") }
    }
    out.append("--- kernel view of each thread (tid comm state utime stime wchan syscall)\n")
    File("/proc/self/task").listFiles()?.sortedBy { it.name.toIntOrNull() ?: 0 }?.forEach { task ->
        val stat = read(File(task, "stat"))
        // "pid (comm) state ... utime(14) stime(15)": comm may hold spaces, so split after the ')'.
        val rest = stat.substringAfterLast(')').trim().split(' ')
        out.append(
            "${task.name} ${stat.substringAfter('(').substringBefore(')')} ${rest.getOrNull(0)} " +
                "${rest.getOrNull(11)} ${rest.getOrNull(12)} wchan=${read(File(task, "wchan"))} " +
                "syscall=${read(File(task, "syscall")).substringBefore(' ')}\n",
        )
    }
    for (name in listOf("/proc/loadavg", "/proc/self/io", "/proc/self/status", "/proc/meminfo", "/proc/vmstat")) {
        out.append("--- $name\n")
        val text = read(File(name))
        out.append(
            if (name == "/proc/vmstat") {
                text
                    .lines()
                    .filter {
                        it.startsWith("nr_dirty") || it.startsWith("nr_writeback") ||
                            it.startsWith("pgmajfault")
                    }.joinToString("\n")
            } else if (name == "/proc/self/status") {
                text.lines().filter { it.startsWith("Vm") || it.startsWith("Threads") }.joinToString("\n")
            } else if (name == "/proc/meminfo") {
                text.lines().take(MEMINFO_LINES).joinToString("\n")
            } else {
                text
            },
        )
        out.append('\n')
    }
    // logcat truncates a long entry: one entry per line.
    out.lines().forEach { Log.e("SealiteStall", it) }
}

private fun read(file: File): String = try {
    file.readText().trim()
} catch (e: java.io.IOException) {
    "<${e.javaClass.simpleName}>"
}

private const val MAX_REPORTS = 3
private const val MEMINFO_LINES = 8
