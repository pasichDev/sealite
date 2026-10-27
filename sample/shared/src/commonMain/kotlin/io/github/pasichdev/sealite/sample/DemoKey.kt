package io.github.pasichdev.sealite.sample

/**
 * DEMO ONLY. The database key is 32 random bytes kept in a plain file in the app-private
 * directory, so anyone who can read that directory can read the notes. That is enough to show
 * how Sealite is wired up and is NOT how a real app should hold a key: a real app keeps the key
 * (or a key that wraps it) in the Android Keystore or the iOS/macOS Keychain, or derives it from
 * something the user types, and never writes it next to the database.
 */
internal object DemoKey {
    private const val FILE_NAME = "demo-database.key"
    const val SIZE = 32

    /** Loads the key from [dir], generating and storing it on first launch. The caller wipes the result. */
    fun loadOrCreate(dir: String): ByteArray {
        Files.mkdirs(dir)
        val path = "$dir/$FILE_NAME"
        Files.read(path)?.let { existing ->
            if (existing.size == SIZE) return existing
            existing.fill(0)
        }
        val fresh = secureRandomBytes(SIZE)
        Files.write(path, fresh)
        return fresh
    }
}
