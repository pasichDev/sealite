// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

// The stall has only been seen on Android; the native targets keep the call and report nothing.
internal actual fun startStallWatchdog(label: String, afterMillis: Long): StallWatchdog = StallWatchdog {}
