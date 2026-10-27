// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@Entity(tableName = "note3")
data class Note3Entity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val body: String,
)

@Dao
interface Note3Dao {
    @Insert
    suspend fun insert(note: Note3Entity): Long

    @Query("SELECT * FROM note3 ORDER BY id")
    suspend fun all(): List<Note3Entity>
}

@Database(entities = [Note3Entity::class], version = 1, exportSchema = false)
abstract class Notes3Database : RoomDatabase() {
    abstract fun notes(): Note3Dao
}

/** The same driver under Room 3 (`androidx.room3`). */
class Room3SealiteTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
    }

    private fun build(path: String, driver: SealiteDriver): Notes3Database = Room
        .databaseBuilder<Notes3Database>(name = path, factory = { Notes3Database_Impl() })
        .setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    @Test
    fun room3WorksThroughTheDriverAndAWrongKeyIsReported() {
        val path = newTempDatabasePath().also { paths += it }
        SealiteDriver(rawKey(1)).use { driver ->
            val db = build(path, driver)
            try {
                runBlocking {
                    db.notes().insert(Note3Entity(body = "first"))
                    db.notes().insert(Note3Entity(body = "second"))
                    assertEquals(listOf("first", "second"), db.notes().all().map { it.body })
                }
            } finally {
                db.close()
            }
        }
        val before = assertNotNull(readFileBytes(path))
        assertFalse(before.startsWith(SQLITE_HEADER), "Room 3 wrote a plaintext database")

        SealiteDriver(rawKey(2)).use { driver ->
            val db = build(path, driver)
            try {
                val e = assertFailsWith<Throwable> { runBlocking { db.notes().all() } }
                assertEquals(SealiteError.WRONG_KEY, Sealite.errorOf(e), "Room 3 threw: $e")
            } finally {
                db.close()
            }
        }
        assertContentEquals(before, readFileBytes(path))

        SealiteDriver(rawKey(1)).use { driver ->
            val db = build(path, driver)
            try {
                assertEquals(2, runBlocking { db.notes().all() }.size)
            } finally {
                db.close()
            }
        }
    }
}
