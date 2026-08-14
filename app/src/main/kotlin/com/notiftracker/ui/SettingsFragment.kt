package com.notiftracker.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.notiftracker.MainActivity
import com.notiftracker.MediaObserver
import com.notiftracker.NotificationService
import com.notiftracker.R
import com.notiftracker.data.Message
import com.notiftracker.data.TrackerRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Onglet Réglages : accès/permissions, diagnostic de capture, export et purge. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var permissionButton: MaterialButton
    private lateinit var audioStatusView: TextView
    private lateinit var repository: TrackerRepository
    private lateinit var mediaObserver: MediaObserver

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TrackerRepository.get(requireContext())
        mediaObserver = MediaObserver(requireContext())

        permissionButton = view.findViewById(R.id.btnPermission)
        audioStatusView = view.findViewById(R.id.tvAudioStatus)

        permissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        view.findViewById<MaterialButton>(R.id.btnStorage).setOnClickListener {
            MainActivity.openStorageAccess(requireActivity() as AppCompatActivity)
        }
        view.findViewById<MaterialButton>(R.id.btnExport).setOnClickListener { export() }
        view.findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            lifecycleScope.launch {
                repository.clearMessages()
                toast(getString(R.string.messages_cleared))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButton()
        updateDiagnostic()
    }

    private fun updatePermissionButton() {
        if (isNotificationAccessGranted()) {
            permissionButton.text = getString(R.string.permission_granted)
            permissionButton.isEnabled = false
        } else {
            permissionButton.text = getString(R.string.permission_request)
            permissionButton.isEnabled = true
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val component = ComponentName(requireContext(), NotificationService::class.java)
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver, "enabled_notification_listeners"
        )
        return enabled?.contains(component.flattenToString()) == true
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun updateDiagnostic() {
        val notif = isNotificationAccessGranted()
        val storage = hasStorageAccess()
        val folder = mediaObserver.isSourceDirectoryAvailable()
        val savedCount = mediaObserver.getSavedMediaCount()

        val notifText = if (notif) ok() else missing()
        val storageText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (storage) ok() else missing()
        } else {
            getString(R.string.diagnostic_legacy_storage)
        }
        val folderText = if (folder) ok() else missing()

        val status = when {
            notif && storage && folder -> DiagnosticStatus.READY
            folder || savedCount > 0 -> DiagnosticStatus.PARTIAL
            else -> DiagnosticStatus.BLOCKED
        }

        audioStatusView.text = getString(
            R.string.audio_diagnostic_summary,
            getString(status.titleRes), notifText, storageText, folderText,
            savedCount, mediaObserver.getSourceDirectoryPath()
        )
        audioStatusView.backgroundTintList =
            ColorStateList.valueOf(requireContext().getColor(status.bgRes))
        audioStatusView.setTextColor(requireContext().getColor(status.textRes))
    }

    private fun ok() = getString(R.string.diagnostic_ok)
    private fun missing() = getString(R.string.diagnostic_missing)

    private fun export() {
        lifecycleScope.launch {
            val messages = repository.allMessages()
            if (messages.isEmpty()) {
                toast(getString(R.string.nothing_to_export))
                return@launch
            }
            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val body = buildString {
                appendLine(getString(R.string.export_title))
                appendLine()
                messages.forEach { m ->
                    appendLine("${getString(R.string.label_sender)}: ${m.sender}")
                    appendLine("${getString(R.string.label_conversation)}: ${m.conversation}")
                    appendLine("${getString(R.string.label_app)}: ${m.app}")
                    appendLine("${getString(R.string.label_status)}: ${statusText(m)}")
                    appendLine("${getString(R.string.label_time)}: ${formatter.format(Date(m.timestamp))}")
                    appendLine("${getString(R.string.label_message)}: ${m.content}")
                    appendLine()
                }
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject))
                putExtra(Intent.EXTRA_TEXT, body)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_chooser)))
        }
    }

    private fun statusText(message: Message): String = if (message.isDeletionMarker) {
        getString(R.string.deletion_marker)
    } else {
        getString(R.string.saved_before_deletion)
    }

    private fun toast(text: String) =
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()

    private enum class DiagnosticStatus(val titleRes: Int, val bgRes: Int, val textRes: Int) {
        READY(R.string.audio_status_ready, R.color.audio_status_ready_bg, R.color.audio_status_ready_text),
        PARTIAL(R.string.audio_status_partial, R.color.audio_status_partial_bg, R.color.audio_status_partial_text),
        BLOCKED(R.string.audio_status_blocked, R.color.audio_status_blocked_bg, R.color.audio_status_blocked_text)
    }
}
