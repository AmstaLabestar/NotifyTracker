package com.notiftracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.notiftracker.ui.MediaFragment
import com.notiftracker.ui.MessagesFragment
import com.notiftracker.ui.SettingsFragment

/**
 * Hote de l'app : barre du haut + navigation par onglets
 * (Messages · Médias · Réglages). Chaque onglet est un fragment.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        // Demarre la capture des medias des l'ouverture.
        MediaCaptureService.start(this)
        requestNotificationsPermission()

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_messages -> MessagesFragment()
                R.id.nav_media -> MediaFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.navHost, fragment)
                .commit()
            true
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_messages
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    companion object {
        /** Ouvre l'ecran systeme d'acces aux fichiers (partage entre Reglages). */
        fun openStorageAccess(activity: AppCompatActivity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            } else {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100
                )
            }
        }
    }
}
