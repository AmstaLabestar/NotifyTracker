package com.notiftracker.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Point d'acces unique aux donnees (messages + medias).
 * Masque Room au reste de l'app et expose des flux reactifs.
 */
class TrackerRepository private constructor(
    private val messageDao: MessageDao,
    private val mediaDao: MediaDao
) {

    val messages: Flow<List<Message>> = messageDao.observeAll()
    val media: Flow<List<MediaEntity>> = mediaDao.observeAll()

    fun mediaOfType(type: MediaType): Flow<List<MediaEntity>> = mediaDao.observeByType(type)

    suspend fun insertMessage(message: Message) = messageDao.insert(message)

    suspend fun allMessages(): List<Message> = messageDao.getAll()

    suspend fun clearMessages() = messageDao.deleteAll()

    suspend fun insertMedia(media: MediaEntity) = mediaDao.insert(media)

    /** Message le plus proche d'un horodatage (± [windowMs] ms), pour relier un media à une conversation. */
    suspend fun nearestMessage(timestamp: Long, windowMs: Long): Message? =
        messageDao.findNearest(timestamp, timestamp - windowMs, timestamp + windowMs)

    companion object {
        @Volatile
        private var INSTANCE: TrackerRepository? = null

        fun get(context: Context): TrackerRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getDatabase(context)
                TrackerRepository(db.messageDao(), db.mediaDao()).also { INSTANCE = it }
            }
        }
    }
}
