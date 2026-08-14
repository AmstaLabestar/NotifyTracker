package com.notiftracker

import android.Manifest
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.notiftracker.data.Message
import com.notiftracker.data.TrackerRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var countView: TextView
    private lateinit var deletedCountView: TextView
    private lateinit var audioStatusView: TextView
    private lateinit var searchInput: TextInputEditText
    private lateinit var deletionOnlySwitch: MaterialSwitch
    private lateinit var adapter: MessageAdapter
    private lateinit var repository: TrackerRepository
    private lateinit var audioObserver: AudioObserver

    private var allMessages: List<Message> = emptyList()
    private var visibleMessages: List<Message> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = TrackerRepository.get(this)

        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.tvEmptyState)
        countView = findViewById(R.id.tvCount)
        deletedCountView = findViewById(R.id.tvDeletedCount)
        audioStatusView = findViewById(R.id.tvAudioStatus)
        searchInput = findViewById(R.id.etSearch)
        deletionOnlySwitch = findViewById(R.id.switchDeletionOnly)
        adapter = MessageAdapter(::showMessageDetails)
        audioObserver = AudioObserver(this)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<MaterialButton>(R.id.btnPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener {
            // La liste se met a jour toute seule (Flow) ; on rafraichit le diagnostic.
            updateAudioDiagnostic()
            applyFilters()
        }

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            lifecycleScope.launch {
                repository.clearMessages()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.messages_cleared),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            exportVisibleMessages()
        }

        searchInput.doAfterTextChanged { applyFilters() }
        deletionOnlySwitch.setOnCheckedChangeListener { _, _ -> applyFilters() }

        // Permission stockage pour l'audio
        requestStoragePermission()
        requestNotificationsPermission()

        // Demarre la capture des vocaux (foreground service) des l'ouverture.
        MediaCaptureService.start(this)

        checkPermission()
        updateAudioDiagnostic()

        findViewById<MaterialButton>(R.id.btnAudios).setOnClickListener {
            startActivity(Intent(this, AudioActivity::class.java))
        }

        // Observe les messages en continu : l'UI se met a jour a chaque capture.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.messages.collect { messages ->
                    allMessages = messages
                    applyFilters()
                }
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                100
            )
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermission()
        updateAudioDiagnostic()
    }

    private fun checkPermission() {
        val btn = findViewById<MaterialButton>(R.id.btnPermission)
        if (isPermissionGranted()) {
            btn.text = getString(R.string.permission_granted)
            btn.isEnabled = false
        } else {
            btn.text = getString(R.string.permission_request)
            btn.isEnabled = true
        }
    }

    private fun isPermissionGranted(): Boolean {
        val componentName = ComponentName(this, NotificationService::class.java)
        val enabledListeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )
        return enabledListeners?.contains(componentName.flattenToString()) == true
    }

    private fun updateAudioDiagnostic() {
        val hasNotificationAccess = isPermissionGranted()
        val hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        val hasWhatsappFolder = audioObserver.isSourceDirectoryAvailable()
        val savedAudioCount = audioObserver.getSavedAudios().size

        val notificationAccess = if (hasNotificationAccess) {
            getString(R.string.diagnostic_ok)
        } else {
            getString(R.string.diagnostic_missing)
        }

        val storageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasStorageAccess) {
                getString(R.string.diagnostic_ok)
            } else {
                getString(R.string.diagnostic_missing)
            }
        } else {
            getString(R.string.diagnostic_legacy_storage)
        }

        val whatsappFolderStatus = if (hasWhatsappFolder) {
            getString(R.string.diagnostic_ok)
        } else {
            getString(R.string.diagnostic_missing)
        }

        val status = when {
            hasNotificationAccess && hasStorageAccess && hasWhatsappFolder -> DiagnosticStatus.READY
            hasWhatsappFolder || savedAudioCount > 0 -> DiagnosticStatus.PARTIAL
            else -> DiagnosticStatus.BLOCKED
        }

        audioStatusView.text = getString(
            R.string.audio_diagnostic_summary,
            getString(status.titleRes),
            notificationAccess,
            storageAccess,
            whatsappFolderStatus,
            savedAudioCount,
            audioObserver.getSourceDirectoryPath()
        )
        audioStatusView.backgroundTintList =
            ColorStateList.valueOf(getColor(status.backgroundColorRes))
        audioStatusView.setTextColor(getColor(status.textColorRes))
    }

    private enum class DiagnosticStatus(
        val titleRes: Int,
        val backgroundColorRes: Int,
        val textColorRes: Int
    ) {
        READY(
            R.string.audio_status_ready,
            R.color.audio_status_ready_bg,
            R.color.audio_status_ready_text
        ),
        PARTIAL(
            R.string.audio_status_partial,
            R.color.audio_status_partial_bg,
            R.color.audio_status_partial_text
        ),
        BLOCKED(
            R.string.audio_status_blocked,
            R.color.audio_status_blocked_bg,
            R.color.audio_status_blocked_text
        )
    }

    private fun applyFilters() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val deletionOnly = deletionOnlySwitch.isChecked

        visibleMessages = allMessages.filter { message ->
            val matchesDeletion = !deletionOnly || message.isDeletionMarker
            val matchesQuery = query.isBlank() || listOf(
                message.sender, message.conversation,
                message.content, message.app
            ).any { it.contains(query, ignoreCase = true) }
            matchesDeletion && matchesQuery
        }

        adapter.submitList(visibleMessages)

        countView.text = getString(R.string.capture_count, allMessages.size)
        deletedCountView.text = getString(
            R.string.deleted_capture_count,
            allMessages.count { it.isDeletionMarker }
        )

        val hasMessages = visibleMessages.isNotEmpty()
        recyclerView.visibility = if (hasMessages) View.VISIBLE else View.GONE
        emptyState.visibility = if (hasMessages) View.GONE else View.VISIBLE
        emptyState.text = if (allMessages.isEmpty()) {
            getString(R.string.empty_state)
        } else {
            getString(R.string.empty_filtered_state)
        }
    }

    private fun exportVisibleMessages() {
        if (visibleMessages.isEmpty()) {
            Toast.makeText(this, getString(R.string.nothing_to_export), Toast.LENGTH_SHORT).show()
            return
        }

        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val exportBody = buildString {
            appendLine(getString(R.string.export_title))
            appendLine()
            visibleMessages.forEach { message ->
                appendLine("${getString(R.string.label_sender)}: ${message.sender}")
                appendLine("${getString(R.string.label_conversation)}: ${message.conversation}")
                appendLine("${getString(R.string.label_app)}: ${message.app}")
                appendLine("${getString(R.string.label_status)}: ${statusText(message)}")
                appendLine("${getString(R.string.label_time)}: ${formatter.format(Date(message.timestamp))}")
                appendLine("${getString(R.string.label_message)}: ${message.content}")
                appendLine()
            }
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject))
            putExtra(Intent.EXTRA_TEXT, exportBody)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_chooser)))
    }

    private fun showMessageDetails(message: Message) {
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val details = buildString {
            appendLine("${getString(R.string.label_sender)}: ${message.sender}")
            appendLine("${getString(R.string.label_conversation)}: ${message.conversation}")
            appendLine("${getString(R.string.label_app)}: ${message.app}")
            appendLine("${getString(R.string.label_status)}: ${statusText(message)}")
            appendLine("${getString(R.string.label_time)}: ${formatter.format(Date(message.timestamp))}")
            appendLine()
            appendLine(message.content)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.details_title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun statusText(message: Message): String {
        return if (message.isDeletionMarker) {
            getString(R.string.deletion_marker)
        } else {
            getString(R.string.saved_before_deletion)
        }
    }
}
