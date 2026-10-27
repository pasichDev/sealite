package io.github.pasichdev.sealite.sample

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow

@Entity
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAtMillis: Long,
)

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM Note ORDER BY id DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("DELETE FROM Note WHERE id = :id")
    suspend fun delete(id: Long)
}

/** The sample's only database. It is opened with `SealiteDriver`, never with a plain SQLite driver. */
@Database(entities = [Note::class], version = 1, exportSchema = false)
@ConstructedBy(SampleDbConstructor::class)
abstract class SampleDb : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}

// Room's KSP processor generates the actual for every target.
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object SampleDbConstructor : RoomDatabaseConstructor<SampleDb> {
    override fun initialize(): SampleDb
}
