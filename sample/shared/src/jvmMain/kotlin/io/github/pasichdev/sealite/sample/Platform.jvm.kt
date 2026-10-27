package io.github.pasichdev.sealite.sample

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

internal actual fun sampleDbBuilder(path: String): RoomDatabase.Builder<SampleDb> =
    Room.databaseBuilder<SampleDb>(name = path)

/** `-Dsealite.sample.dir=...` overrides the location (tests, headless runs). */
actual fun appDataDir(): String {
    System.getProperty("sealite.sample.dir")?.takeIf { it.isNotBlank() }?.let { return it }
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        os.contains("mac") -> File(home, "Library/Application Support/SealiteSample")
        os.contains("win") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "SealiteSample")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "sealite-sample")
    }
    return base.absolutePath
}
