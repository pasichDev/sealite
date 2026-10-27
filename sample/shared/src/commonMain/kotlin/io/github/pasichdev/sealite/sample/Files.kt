package io.github.pasichdev.sealite.sample

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Whole-file helpers over kotlinx-io. The files here are small. */
internal object Files {
    fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

    fun size(path: String): Long? = SystemFileSystem.metadataOrNull(Path(path))?.size

    fun read(path: String): ByteArray? {
        val p = Path(path)
        if (!SystemFileSystem.exists(p)) return null
        return SystemFileSystem.source(p).buffered().use { it.readByteArray() }
    }

    fun write(path: String, bytes: ByteArray) {
        SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }
    }

    fun delete(path: String) {
        SystemFileSystem.delete(Path(path), mustExist = false)
    }

    fun mkdirs(path: String) {
        SystemFileSystem.createDirectories(Path(path))
    }

    /** The database file plus the sidecar files SQLite may create next to it. */
    fun deleteDatabase(path: String) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) delete(path + suffix)
    }
}
