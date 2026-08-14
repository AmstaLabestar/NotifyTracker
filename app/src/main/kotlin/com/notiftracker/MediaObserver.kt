package com.notiftracker

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import com.notiftracker.data.MediaEntity
import com.notiftracker.data.MediaType
import com.notiftracker.data.TrackerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Surveille les dossiers media de WhatsApp (vocaux, photos, videos) et copie les
 * nouveaux fichiers dans le dossier prive de l'app, tout en enregistrant une ligne
 * [MediaEntity] en base (avec correlation a la conversation la plus proche).
 *
 * Fiabilite (voir PLAN.md) : `FileObserver` (compat API 26+) pour la reactivite +
 * scan periodique pour garantir la copie sur Android 11+ (stockage scoped). Aucune
 * attente bloquante sur le thread de callback (stabilite via l'age du fichier).
 */
class MediaObserver(private val context: Context) {

    private val repository by lazy { TrackerRepository.get(context) }
    private val fileObservers = mutableListOf<FileObserver>()
    private var scheduler: ScheduledExecutorService? = null
    private var scope: CoroutineScope? = null

    private val ourMediaRoot = File(context.getExternalFilesDir(null), "captured_media")
        .also { it.mkdirs() }

    // Racines media WhatsApp + WhatsApp Business.
    private val whatsappBases = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"
    ).map { File(Environment.getExternalStorageDirectory(), it) }

    fun start() {
        stop()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val sources = discoverSources()
        if (sources.isEmpty()) {
            Log.w(TAG, "Aucun dossier media WhatsApp trouve (scan periodique actif)")
        }
        sources.forEach { watchDirectory(it.dir) }

        scheduler = Executors.newSingleThreadScheduledExecutor().also { exec ->
            exec.scheduleWithFixedDelay(
                { safeScan() }, 0L, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS
            )
        }
    }

    fun stop() {
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
        scheduler?.shutdownNow()
        scheduler = null
        scope?.cancel()
        scope = null
    }

    private fun safeScan() {
        try {
            val copied = scanAndCopyNew()
            if (copied > 0) Log.d(TAG, "$copied nouveau(x) media copie(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant le scan : ${e.message}")
        }
    }

    /** Copie les medias pas encore sauvegardes et cree leur ligne en base. */
    @Synchronized
    fun scanAndCopyNew(): Int {
        var copied = 0
        discoverSources().forEach { source ->
            source.dir.listFiles()?.forEach { file ->
                if (file.isFile && isSupported(file, source.type)) {
                    val dest = copyIfNew(file, source.type)
                    if (dest != null) {
                        recordMedia(dest, file, source.type)
                        copied++
                    }
                }
            }
        }
        return copied
    }

    private fun recordMedia(dest: File, source: File, type: MediaType) {
        val timestamp = source.lastModified()
        scope?.launch {
            val nearest = runCatching {
                repository.nearestMessage(timestamp, CORRELATION_WINDOW_MS)
            }.getOrNull()
            repository.insertMedia(
                MediaEntity(
                    type = type,
                    localPath = dest.absolutePath,
                    sourceName = source.name,
                    sizeBytes = dest.length(),
                    timestamp = timestamp,
                    conversation = nearest?.conversation,
                    sender = nearest?.sender,
                    linkedMessageId = nearest?.id
                )
            )
        }
    }

    private fun watchDirectory(directory: File) {
        if (!directory.exists() || !directory.isDirectory) return

        val mask = FileObserver.CREATE or FileObserver.CLOSE_WRITE
        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, mask) {
                override fun onEvent(event: Int, path: String?) = handleEvent(directory, event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(directory.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = handleEvent(directory, event, path)
            }
        }
        observer.startWatching()
        fileObservers += observer
        Log.d(TAG, "Surveillance de : ${directory.path}")
    }

    private fun handleEvent(directory: File, event: Int, path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(directory, path)
        if (file.isDirectory && event == FileObserver.CREATE) {
            watchDirectory(file)
            return
        }
        // Copie deleguee au scan (hors thread de callback).
        scheduler?.submit { safeScan() }
    }

    private fun isSupported(file: File, type: MediaType): Boolean {
        val name = file.name.lowercase()
        return when (type) {
            MediaType.AUDIO -> AUDIO_EXT.any { name.endsWith(it) }
            MediaType.PHOTO -> PHOTO_EXT.any { name.endsWith(it) }
            MediaType.VIDEO -> VIDEO_EXT.any { name.endsWith(it) }
        }
    }

    /** Copie le fichier s'il est stable et absent ; retourne la destination ou null. */
    private fun copyIfNew(source: File, type: MediaType): File? {
        if (!source.exists() || source.length() == 0L) return null

        // Evite de copier un fichier encore en cours d'ecriture.
        if (System.currentTimeMillis() - source.lastModified() < STABILITY_DELAY_MS) return null

        val typeDir = File(ourMediaRoot, type.name.lowercase()).also { it.mkdirs() }
        val dest = File(typeDir, source.name)
        if (dest.exists() && dest.length() == source.length()) return null

        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Media copie : ${dest.path}")
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Erreur copie media : ${e.message}")
            null
        }
    }

    // --- Helpers pour le diagnostic (MainActivity) ---

    fun isSourceDirectoryAvailable(): Boolean = discoverSources().isNotEmpty()

    fun getSourceDirectoryPath(): String = discoverSources()
        .joinToString(separator = "\n") { it.dir.absolutePath }
        .ifBlank { whatsappBases.first().resolve("WhatsApp Voice Notes").absolutePath }

    /** Nombre de fichiers deja copies dans notre dossier prive. */
    fun getSavedMediaCount(): Int = MediaType.values().sumOf { type ->
        File(ourMediaRoot, type.name.lowercase()).listFiles()?.count { it.isFile } ?: 0
    }

    private data class Source(val dir: File, val type: MediaType)

    private fun discoverSources(): List<Source> {
        val sources = linkedSetOf<Source>()
        whatsappBases.filter { it.isDirectory }.forEach { base ->
            addSource(sources, base, "WhatsApp Voice Notes", MediaType.AUDIO, recurse = true)
            addSource(sources, base, "WhatsApp Audio", MediaType.AUDIO, recurse = false)
            addSource(sources, base, "WhatsApp Images", MediaType.PHOTO, recurse = false)
            addSource(sources, base, "WhatsApp Video", MediaType.VIDEO, recurse = false)
        }
        return sources.toList()
    }

    private fun addSource(
        into: MutableSet<Source>,
        base: File,
        subPath: String,
        type: MediaType,
        recurse: Boolean
    ) {
        val dir = File(base, subPath)
        if (!dir.isDirectory) return
        into += Source(dir, type)
        if (recurse) {
            // Vocaux : sous-dossiers datés ; on ignore "Sent" (nos propres envois).
            dir.listFiles()
                ?.filter { it.isDirectory && !it.name.equals("Sent", ignoreCase = true) }
                ?.forEach { into += Source(it, type) }
        }
    }

    companion object {
        private const val TAG = "MediaObserver"
        private const val POLL_INTERVAL_SECONDS = 3L
        private const val STABILITY_DELAY_MS = 1_000L
        private const val CORRELATION_WINDOW_MS = 120_000L
        private val AUDIO_EXT = listOf(".opus", ".mp3", ".aac", ".m4a", ".ogg")
        private val PHOTO_EXT = listOf(".jpg", ".jpeg", ".png", ".webp")
        private val VIDEO_EXT = listOf(".mp4", ".3gp", ".mkv")
    }
}
