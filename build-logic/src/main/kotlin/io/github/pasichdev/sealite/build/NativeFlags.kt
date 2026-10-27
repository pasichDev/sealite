// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

/** Compile flags shared by every native target. */
object NativeFlags {
    /** The flags androidx.sqlite:sqlite-bundled compiles SQLite with. */
    val androidxSqlite =
        listOf(
            "-DHAVE_USLEEP=1",
            "-DSQLITE_DEFAULT_AUTOVACUUM=1",
            "-DSQLITE_DEFAULT_MEMSTATUS=0",
            "-DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1",
            "-DSQLITE_ENABLE_COLUMN_METADATA",
            "-DSQLITE_ENABLE_FTS3",
            "-DSQLITE_ENABLE_FTS3_PARENTHESIS",
            "-DSQLITE_ENABLE_FTS4",
            "-DSQLITE_ENABLE_FTS5",
            "-DSQLITE_ENABLE_JSON1",
            "-DSQLITE_ENABLE_MATH_FUNCTIONS",
            "-DSQLITE_ENABLE_NORMALIZE",
            "-DSQLITE_ENABLE_RTREE",
            "-DSQLITE_ENABLE_STAT4",
            "-DSQLITE_HAVE_ISNAN",
            "-DSQLITE_OMIT_BUILTIN_TEST",
            "-DSQLITE_OMIT_DEPRECATED",
            "-DSQLITE_OMIT_PROGRESS_CALLBACK",
            "-DSQLITE_OMIT_SHARED_CACHE",
            "-DSQLITE_SECURE_DELETE",
            "-DSQLITE_TEMP_STORE=3",
            "-DSQLITE_THREADSAFE=2",
        )

    /** What SQLCipher needs on top (see its README, "Compiling"). */
    val sqlcipher =
        listOf(
            "-DSQLITE_HAS_CODEC",
            "-DSQLITE_TEMP_STORE=3",
            "-DSQLITE_EXTRA_INIT=sqlcipher_extra_init",
            "-DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown",
            "-DSQLITE_THREADSAFE=2",
            "-DSQLITE_OMIT_LOAD_EXTENSION",
        )

    /** Flags for the amalgamation: androidx list + SQLCipher list + the crypto provider. */
    fun amalgamation(cryptoProviderDefine: String): List<String> =
        (androidxSqlite + sqlcipher + cryptoProviderDefine.let { "-D$it" }).distinct()
}
