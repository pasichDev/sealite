// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

@file:JvmName("NativeRenameKt")

package io.github.pasichdev.sealite.internal

/**
 * `renameat2(RENAME_NOREPLACE)` through the Sealite library: 0 when [from] was renamed to [to], the
 * `errno` otherwise (`EEXIST` when [to] exists). Both arguments are UTF-8 paths. Only call it on
 * API 30 and later: the seccomp filter of API 26 to 29 may kill the process on this system call.
 */
private external fun nativeRenameNoReplace(from: ByteArray, to: ByteArray): Int

internal fun renameNoReplace(from: String, to: String): Int =
    nativeRenameNoReplace(from.encodeToByteArray(), to.encodeToByteArray())
