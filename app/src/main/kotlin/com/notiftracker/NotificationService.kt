package com.notiftracker

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

class NotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val trackedApps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val appName = trackedApps[packageName] ?: return

        val payload = extractPayload(sbn.notification.extras) ?: return
        val fingerprint = buildFingerprint(
            packageName = packageName,
            sender = payload.sender,
            conversation = payload.conversation,
            content = payload.message,
            timestamp = sbn.postTime
        )

        val message = Message(
            sender = payload.sender,
            content = payload.message,
            timestamp = sbn.postTime,
            app = appName,
            packageName = packageName,
            conversation = payload.conversation,
            fingerprint = fingerprint,
            isDeletionMarker = payload.isDeletionMarker
        )

        scope.launch {
            MessageDatabase.getDatabase(applicationContext)
                .messageDao()
                .insert(message)
        }
    }

    private fun extractPayload(extras: Bundle): NotificationPayload? {
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString().trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val messagingStyle = extractMessagingStyleMessage(extras)
        val fallbackText = listOf(bigText, text, textLines.lastOrNull().orEmpty())
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        val rawMessage = listOf(messagingStyle.message, fallbackText)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return null

        val message = cleanupMessage(rawMessage)
        if (shouldIgnoreMessage(message)) return null

        val conversation = subText.ifBlank {
            messagingStyle.conversation.ifBlank { title.ifBlank { "Conversation inconnue" } }
        }
        val sender = messagingStyle.sender.ifBlank { title.ifBlank { conversation } }
        val isDeletionMarker = deletedMarkers.any { marker ->
            message.contains(marker, ignoreCase = true)
        }

        return NotificationPayload(
            sender = sender,
            conversation = conversation,
            message = message,
            isDeletionMarker = isDeletionMarker
        )
    }

    private fun extractMessagingStyleMessage(extras: Bundle): MessagingStylePayload {
        val parcelables = extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty()
        val bundles = parcelables.mapNotNull { it as? Bundle }
        val lastMessage = bundles.lastOrNull()

        val sender = extractSender(lastMessage)
        val message = lastMessage?.getCharSequence("text")?.toString()?.trim().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            ?.trim()
            .orEmpty()

        return MessagingStylePayload(
            sender = sender,
            conversation = conversation,
            message = message
        )
    }

    private fun extractSender(messageBundle: Bundle?): String {
        if (messageBundle == null) return ""

        val person = messageBundle.getParcelable("sender_person") as? Parcelable
        val personName = when (person) {
            is android.app.Person -> person.name?.toString().orEmpty()
            else -> ""
        }

        return listOf(
            personName,
            messageBundle.getCharSequence("sender")?.toString().orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun cleanupMessage(rawMessage: String): String {
        val trimmed = rawMessage.trim()
        val separatorIndex = trimmed.indexOf(": ")

        if (separatorIndex <= 0) return trimmed

        val prefix = trimmed.substring(0, separatorIndex)
        val suffix = trimmed.substring(separatorIndex + 2)

        return if (prefix.length in 1..60 && suffix.isNotBlank()) suffix.trim() else trimmed
    }

    private fun shouldIgnoreMessage(message: String): Boolean {
        if (message.isBlank()) return true
        if (ignoredExactMessages.any { it.equals(message, ignoreCase = true) }) return true
        if (ignoredPrefixes.any { prefix -> message.startsWith(prefix, ignoreCase = true) }) return true
        return false
    }

    private fun buildFingerprint(
        packageName: String,
        sender: String,
        conversation: String,
        content: String,
        timestamp: Long
    ): String {
        val raw = "$packageName|$sender|$conversation|$content|${timestamp / 1000}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private data class MessagingStylePayload(
        val sender: String,
        val conversation: String,
        val message: String
    )

    private data class NotificationPayload(
        val sender: String,
        val conversation: String,
        val message: String,
        val isDeletionMarker: Boolean
    )

    companion object {
        private val ignoredExactMessages = setOf(
            "Nouveau message",
            "Nouveaux messages",
            "New message",
            "New messages"
        )

        private val ignoredPrefixes = setOf(
            "Checking for new messages",
            "Recherche de nouveaux messages"
        )

        private val deletedMarkers = setOf(
            "This message was deleted",
            "Ce message a ete supprime",
            "You deleted this message",
            "Vous avez supprime ce message"
        )
    }
}
