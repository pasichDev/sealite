package io.github.pasichdev.sealite.sample

import androidx.room.RoomDatabase

/** Cryptographically secure random bytes from the operating system. */
internal expect fun secureRandomBytes(size: Int): ByteArray

/** A Room builder for [SampleDb] at an absolute file [path]; the caller sets the driver. */
internal expect fun sampleDbBuilder(path: String): RoomDatabase.Builder<SampleDb>
