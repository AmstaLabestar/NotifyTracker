package com.notiftracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service qui heberge la surveillance des vocaux WhatsApp.
 *
 * Pourquoi un foreground service (voir PLAN.md, Phase 0) : le
 * NotificationListenerService peut etre recycle par le systeme, ce qui coupe la
 * surveillance des fichiers. Un foreground service avec notification persistante
 * garde la capture active de facon fiable.
 */
class MediaCaptureService : Service() {

    private lateinit var audioObserver: AudioObserver

    override fun onCreate() {
        super.onCreate()
        audioObserver = AudioObserver(applicationContext)
        startForegroundNotification()
        audioObserver.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Redemarre automatiquement si le systeme tue le service.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        audioObserver.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        createChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_service_title))
            .setContentText(getString(R.string.capture_service_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_service_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.capture_service_text)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "media_capture"
        private const val NOTIFICATION_ID = 42

        /** Demarre le service en foreground de maniere sure depuis un contexte actif. */
        fun start(context: Context) {
            val intent = Intent(context, MediaCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
