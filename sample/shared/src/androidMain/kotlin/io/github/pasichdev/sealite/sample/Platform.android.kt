package io.github.pasichdev.sealite.sample

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.security.SecureRandom

/** Set by the Android app before [App] is shown. */
object AndroidContextHolder {
    lateinit var context: Context
}

actual fun appDataDir(): String = AndroidContextHolder.context.filesDir.absolutePath

private val random = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

internal actual fun sampleDbBuilder(path: String): RoomDatabase.Builder<SampleDb> =
    Room.databaseBuilder<SampleDb>(AndroidContextHolder.context, path)
