package com.notiftracker

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Surveille les dossiers media de WhatsApp et copie les vocaux dans un dossier
 * prive de l'app.
 *
 * Fiabilite (voir PLAN.md, Phase 0) :
 *  - `FileObserver` seul n'est pas fiable sur Android 11+ pour les fichiers ecrits
 *    par une AUTRE app (stockage scoped / FUSE). On l'utilise pour la reactivite,
 *    mais un scan periodique ([scanAndCopyNew]) garantit la copie.
 *  - Constructeur `FileObserver(File, mask)` = API 29+ ; sur API 26-28 on passe par
 *    le constructeur `String` (deprecie mais present).
 *  - Aucun `Thread.sleep` sur un thread de callback : la stabilite du fichier est
 *    verifiee via l'age de derniere modification.
 */
class AudioObserver(private val context: Context) {

    private val fileObservers = mutableListOf<FileObserver>()
    private var scheduler: ScheduledExecutorService? = null

    // Sur certains appareils, WhatsApp range les vocaux dans Voice Notes
    // avec des sous-dossiers par date ou "recent".
    private val whatsappVoiceNotesDir = File(
        Environment.getExternalStorageDirectory(),
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"
    )

    // Garde l'ancien chemin comme fallback.
    private val legacyWhatsappAudioDir = File(
        Environment.getExternalStorageDirectory(),
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"
    )

    // Dossier prive de notre app ou on copie les audios
    private val ourAudioDir = File(
        context.getExternalFilesDir(null),
        "saved_audio"
    ).also { it.mkdirs() }

    /** Demarre la surveillance : FileObserver (reactivite) + scan periodique (fiabilite). */
    fun start() {
        stop()

        val sourceDirectories = discoverSourceDirectories()
        if (sourceDirectories.isEmpty()) {
            Log.w(TAG, "Aucun dossier audio WhatsApp trouve pour l'instant (scan periodique actif)")
        }
        sourceDirectories.forEach { watchDirectory(it) }

        scheduler = Executors.newSingleThreadScheduledExecutor().also { exec ->
            exec.scheduleWithFixedDelay(
                { safeScan() },
                0L,
                POLL_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    fun stop() {
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
        scheduler?.shutdownNow()
        scheduler = null
    }

    // Conserve pour compat avec l'ancien appelant ; delegue a start().
    fun startWatching() = start()
    fun stopWatching() = stop()

    private fun safeScan() {
        try {
            val copied = scanAndCopyNew()
            if (copied > 0) Log.d(TAG, "$copied nouveau(x) audio(s) copie(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant le scan : ${e.message}")
        }
    }

    /**
     * Parcourt les dossiers sources et copie les vocaux pas encore sauvegardes.
     * Idempotent : on saute un fichier deja copie (meme nom present dans notre dossier).
     * @return nombre de fichiers copies pendant cet appel.
     */
    @Synchronized
    fun scanAndCopyNew(): Int {
        var copied = 0
        discoverSourceDirectories().forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && isSupportedAudioFile(file) && copyIfNew(file)) {
                    copied++
                }
            }
        }
        return copied
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
        // On ne copie pas ici (thread de callback) : on delegue au scan sur le scheduler.
        if (isSupportedAudioFile(file)) {
            scheduler?.submit { safeScan() }
        }
    }

    private fun isSupportedAudioFile(file: File): Boolean {
        val name = file.name
        return name.endsWith(".opus", ignoreCase = true) ||
            name.endsWith(".mp3", ignoreCase = true) ||
            name.endsWith(".aac", ignoreCase = true) ||
            name.endsWith(".m4a", ignoreCase = true) ||
            name.endsWith(".ogg", ignoreCase = true)
    }

    /** Copie le fichier s'il est stable et pas deja present. */
    private fun copyIfNew(source: File): Boolean {
        if (!source.exists() || source.length() == 0L) return false

        // Stabilite : on evite de copier un fichier encore en cours d'ecriture.
        val ageMs = System.currentTimeMillis() - source.lastModified()
        if (ageMs < STABILITY_DELAY_MS) return false

        val destFile = File(ourAudioDir, source.name)
        if (destFile.exists() && destFile.length() == source.length()) return false

        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Audio copie : ${destFile.path}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur copie audio : ${e.message}")
            false
        }
    }

    fun getSavedAudios(): List<File> {
        return ourAudioDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun isSourceDirectoryAvailable(): Boolean {
        return discoverSourceDirectories().isNotEmpty()
    }

    fun getSourceDirectoryPath(): String {
        return discoverSourceDirectories()
            .joinToString(separator = "\n") { it.absolutePath }
            .ifBlank { whatsappVoiceNotesDir.absolutePath }
    }

    private fun discoverSourceDirectories(): List<File> {
        val directories = linkedSetOf<File>()

        if (whatsappVoiceNotesDir.exists() && whatsappVoiceNotesDir.isDirectory) {
            directories += whatsappVoiceNotesDir
            whatsappVoiceNotesDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { directories += it }
        }

        if (legacyWhatsappAudioDir.exists() && legacyWhatsappAudioDir.isDirectory) {
            directories += legacyWhatsappAudioDir
        }

        return directories.toList()
    }

    companion object {
        private const val TAG = "AudioObserver"
        private const val POLL_INTERVAL_SECONDS = 3L
        private const val STABILITY_DELAY_MS = 1_000L
    }
}
