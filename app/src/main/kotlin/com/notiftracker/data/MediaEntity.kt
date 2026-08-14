package com.notiftracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Type de media capture depuis WhatsApp. */
enum class MediaType {
    AUDIO,
    PHOTO,
    VIDEO
}

/**
 * Un fichier media copie dans le dossier prive de l'app.
 *
 * `localPath` est unique : c'est la garantie anti-doublon cote base (en plus de la
 * deduplication cote fichier dans l'observer).
 * `conversation` / `sender` / `linkedMessageId` sont optionnels : ils seront remplis
 * en Phase 2 quand on correlera les medias aux notifications.
 */
@Entity(
    tableName = "media",
    indices = [Index(value = ["localPath"], unique = true)]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val type: MediaType,
    val localPath: String,
    val sourceName: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val conversation: String? = null,
    val sender: String? = null,
    val linkedMessageId: Int? = null
)
