// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Entity(tableName = "note")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val body: String,
)

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM note ORDER BY id")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT count(*) FROM note")
    suspend fun count(): Int
}

@Database(entities = [NoteEntity::class], version = 1, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
}

class RoomSealiteTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
    }

    private fun build(
        path: String,
        driver: SealiteDriver,
        journalMode: RoomDatabase.JournalMode = RoomDatabase.JournalMode.TRUNCATE,
    ): NotesDatabase = Room
        .databaseBuilder<NotesDatabase>(name = path, factory = { NotesDatabase_Impl() })
        .setDriver(driver)
        .fallbackToDestructiveMigration(true)
        .fallbackToDestructiveMigrationOnDowngrade(true)
        .setJournalMode(journalMode)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    private fun key(seed: Int) = SealiteKey.raw(ByteArray(32) { (it + seed).toByte() })

    @Test
    fun roomWorksThroughTheDriverAndAWrongKeyThrowsWithoutTouchingTheFile() {
        val path = newTempDatabasePath().also { paths += it }

        SealiteDriver(key(1)).use { driver ->
            val db = build(path, driver)
            try {
                runBlocking {
                    db.notes().insert(NoteEntity(body = "first"))
                    db.notes().insert(NoteEntity(body = "second"))
                    assertEquals(listOf("first", "second"), db.notes().all().map { it.body })
                }
            } finally {
                db.close()
            }
        }

        val before = assertNotNull(readFileBytes(path))
        assertFalse(before.startsWith(SQLITE_HEADER), "Room wrote a plaintext database")

        SealiteDriver(key(2)).use { driver ->
            val db = build(path, driver)
            try {
                val e = assertFailsWith<Throwable> { runBlocking { db.notes().all() } }
                assertEquals(SealiteError.WRONG_KEY, Sealite.errorOf(e), "Room threw: $e")
            } finally {
                db.close()
            }
        }

        val after = assertNotNull(readFileBytes(path))
        assertEquals(before.size, after.size)
        assertContentEquals(before, after)

        // The right key still reads everything.
        SealiteDriver(key(1)).use { driver ->
            val db = build(path, driver)
            try {
                assertEquals(2, runBlocking { db.notes().all() }.size)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun aWrongKeyNeverMakesRoomDeleteTheDatabaseEvenWithDestructiveFallbacksOn() {
        // The builder allows destructive migration and downgrade recovery (build() above): a wrong
        // key is a SealiteException, not a SQLiteException, so Room has nothing to recover from.
        val path = newTempDatabasePath().also { paths += it }
        SealiteDriver(key(1)).use { driver ->
            val db = build(path, driver)
            try {
                runBlocking { db.notes().insert(NoteEntity(body = "precious")) }
            } finally {
                db.close()
            }
        }
        val before = assertNotNull(readFileBytes(path))
        repeat(3) {
            SealiteDriver(key(2)).use { driver ->
                val db = build(path, driver)
                try {
                    val e = assertFailsWith<Throwable> { runBlocking { db.notes().all() } }
                    assertEquals(SealiteError.WRONG_KEY, Sealite.errorOf(e), "Room threw: $e")
                    assertTrue(generateSequence(e) { it.cause }.any { it is SealiteException }, "Room threw: $e")
                } finally {
                    db.close()
                }
            }
            assertContentEquals(before, readFileBytes(path), "the database changed or was deleted")
        }
        SealiteDriver(key(1)).use { driver ->
            val db = build(path, driver)
            try {
                assertEquals(listOf("precious"), runBlocking { db.notes().all() }.map { it.body })
            } finally {
                db.close()
            }
        }
    }

    private fun concurrentReadsWhileWriting(journalMode: RoomDatabase.JournalMode) {
        val path = newTempDatabasePath().also { paths += it }
        val total = 150
        SealiteDriver(key(3)).use { driver ->
            val db = build(path, driver, journalMode)
            try {
                runBlocking {
                    coroutineScope {
                        val writer =
                            async(Dispatchers.IO) {
                                for (i in 1..total) db.notes().insert(NoteEntity(body = "$MARKER-$i"))
                            }
                        val readers =
                            List(4) {
                                async(Dispatchers.IO) {
                                    var last = 0
                                    // Read until the writer is done, yielding between reads: without a
                                    // WAL the readers and the writer share one connection, and readers
                                    // that never yield can starve the writer forever.
                                    while (!writer.isCompleted) {
                                        val rows = db.notes().all()
                                        // Every read sees a consistent prefix of the writes.
                                        assertTrue(rows.size >= last, "rows went backwards: ${rows.size} < $last")
                                        rows.forEachIndexed { i, row -> assertEquals("$MARKER-${i + 1}", row.body) }
                                        assertTrue(db.notes().count() >= rows.size)
                                        last = rows.size
                                        yield()
                                    }
                                    assertEquals(total, db.notes().all().size)
                                }
                            }
                        (readers + writer).awaitAll()
                    }
                    assertEquals(total, db.notes().count())
                }
            } finally {
                db.close()
            }
        }
        val bytes = assertNotNull(readFileBytes(path))
        assertFalse(bytes.startsWith(SQLITE_HEADER))
        // A long marker: a short one (a few bytes) turns up in random ciphertext by chance.
        assertFalse(bytes.contains(MARKER.encodeToByteArray()), "plaintext found in the database file")
        SealiteDriver(key(3)).use { driver ->
            val db = build(path, driver, journalMode)
            try {
                assertEquals(total, runBlocking { db.notes().count() })
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun concurrentReadsWhileWritingInRollbackJournalMode() =
        concurrentReadsWhileWriting(RoomDatabase.JournalMode.TRUNCATE)

    @Test
    fun concurrentReadsWhileWritingInWal() = concurrentReadsWhileWriting(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

    @Test
    fun aPlaintextDatabaseThroughRoomIsReportedAsNotEncrypted() {
        val path = newTempDatabasePath().also { paths += it }
        writeFileBytes(path, PLAINTEXT_DATABASE)
        SealiteDriver(key(1)).use { driver ->
            val db = build(path, driver)
            try {
                val e = assertFailsWith<Throwable> { runBlocking { db.notes().all() } }
                assertEquals(SealiteError.NOT_ENCRYPTED, Sealite.errorOf(e), "Room threw: $e")
            } finally {
                db.close()
            }
        }
        assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(path), "Room must not touch a plaintext file")
    }
}

/** Long enough that random ciphertext can not contain it by chance. */
private const val MARKER = "sealite-room-plaintext-marker"
