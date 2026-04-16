package com.notiftracker

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AudioObserver(private val context: Context) {

    private var fileObserver: FileObserver? = null

    // Dossier où WhatsApp stocke les audios
    private val whatsappAudioDir = File(
        Environment.getExternalStorageDirectory(),
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"
    )

    // Dossier privé de notre app où on copie les audios
    private val ourAudioDir = File(
        context.getExternalFilesDir(null),
        "saved_audio"
    ).also { it.mkdirs() }

    fun startWatching() {
        if (!whatsappAudioDir.exists()) {
            Log.e("AudioObserver", "Dossier WhatsApp Audio introuvable : ${whatsappAudioDir.path}")
            return
        }

        Log.d("AudioObserver", "Surveillance de : ${whatsappAudioDir.path}")

        fileObserver = object : FileObserver(whatsappAudioDir, CREATE or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (!path.endsWith(".opus") && !path.endsWith(".mp3") && !path.endsWith(".aac")) return

                if (event == CLOSE_WRITE || event == CREATE) {
                    val sourceFile = File(whatsappAudioDir, path)
                    copyAudio(sourceFile)
                }
            }
        }

        fileObserver?.startWatching()
    }

    fun stopWatching() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    private fun copyAudio(source: File) {
        try {
            // Attendre que le fichier soit complètement écrit
            Thread.sleep(500)

            if (!source.exists() || source.length() == 0L) return

            val destFile = File(ourAudioDir, "audio_${System.currentTimeMillis()}_${source.name}")

            FileInputStream(source).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d("AudioObserver", "Audio copié : ${destFile.path}")

        } catch (e: Exception) {
            Log.e("AudioObserver", "Erreur copie audio : ${e.message}")
        }
    }

    fun getSavedAudios(): List<File> {
        return ourAudioDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}