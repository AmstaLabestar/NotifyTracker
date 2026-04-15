package com.notiftracker

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["fingerprint"], unique = true)
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val app: String,
    val packageName: String,
    val conversation: String,
    val fingerprint: String,
    val isDeletionMarker: Boolean = false
)
