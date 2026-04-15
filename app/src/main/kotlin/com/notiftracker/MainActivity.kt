package com.notiftracker

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var countView: TextView
    private lateinit var deletedCountView: TextView
    private lateinit var searchInput: TextInputEditText
    private lateinit var deletionOnlySwitch: MaterialSwitch
    private lateinit var adapter: MessageAdapter
    private lateinit var db: MessageDatabase

    private var allMessages: List<Message> = emptyList()
    private var visibleMessages: List<Message> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = MessageDatabase.getDatabase(this)

        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.tvEmptyState)
        countView = findViewById(R.id.tvCount)
        deletedCountView = findViewById(R.id.tvDeletedCount)
        searchInput = findViewById(R.id.etSearch)
        deletionOnlySwitch = findViewById(R.id.switchDeletionOnly)
        adapter = MessageAdapter(::showMessageDetails)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<MaterialButton>(R.id.btnPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener {
            loadMessages()
        }

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            lifecycleScope.launch {
                db.messageDao().deleteAll()
                loadMessages()
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

        searchInput.doAfterTextChanged {
            applyFilters()
        }

        deletionOnlySwitch.setOnCheckedChangeListener { _, _ ->
            applyFilters()
        }

        checkPermission()
        loadMessages()
    }

    override fun onResume() {
        super.onResume()
        checkPermission()
        loadMessages()
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
            contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(componentName.flattenToString()) == true
    }

    private fun loadMessages() {
        lifecycleScope.launch {
            allMessages = db.messageDao().getAll()
            applyFilters()
        }
    }

    private fun applyFilters() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val deletionOnly = deletionOnlySwitch.isChecked

        visibleMessages = allMessages.filter { message ->
            val matchesDeletion = !deletionOnly || message.isDeletionMarker
            val matchesQuery = query.isBlank() || listOf(
                message.sender,
                message.conversation,
                message.content,
                message.app
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
