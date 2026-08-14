package com.notiftracker.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: Message)

    /** Flux reactif : l'UI se met a jour automatiquement a chaque insertion. */
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<Message>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    suspend fun getAll(): List<Message>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(media: MediaEntity)

    @Query("SELECT * FROM media ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE type = :type ORDER BY timestamp DESC")
    fun observeByType(type: MediaType): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media ORDER BY timestamp DESC")
    suspend fun getAll(): List<MediaEntity>

    @Query("DELETE FROM media")
    suspend fun deleteAll()
}

class Converters {
    @TypeConverter
    fun mediaTypeToString(type: MediaType): String = type.name

    @TypeConverter
    fun stringToMediaType(value: String): MediaType = MediaType.valueOf(value)
}

@Database(
    entities = [Message::class, MediaEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "messages_db"
                )
                    // Migration destructive assumee (app personnelle, voir PLAN.md Phase 1).
                    // A remplacer par une vraie migration si les donnees deviennent precieuses.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
