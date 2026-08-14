package com.notiftracker.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.notiftracker.MessageAdapter
import com.notiftracker.R
import com.notiftracker.data.Message
import com.notiftracker.data.TrackerRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Onglet Messages : liste des captures texte, recherche et filtre suppressions. */
class MessagesFragment : Fragment(R.layout.fragment_messages) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var countView: TextView
    private lateinit var deletedCountView: TextView
    private lateinit var searchInput: TextInputEditText
    private lateinit var deletionOnlySwitch: MaterialSwitch
    private lateinit var adapter: MessageAdapter
    private lateinit var repository: TrackerRepository

    private var allMessages: List<Message> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TrackerRepository.get(requireContext())

        recyclerView = view.findViewById(R.id.recyclerView)
        emptyState = view.findViewById(R.id.tvEmptyState)
        countView = view.findViewById(R.id.tvCount)
        deletedCountView = view.findViewById(R.id.tvDeletedCount)
        searchInput = view.findViewById(R.id.etSearch)
        deletionOnlySwitch = view.findViewById(R.id.switchDeletionOnly)

        adapter = MessageAdapter(::showMessageDetails)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        searchInput.doAfterTextChanged { applyFilters() }
        deletionOnlySwitch.setOnCheckedChangeListener { _, _ -> applyFilters() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.messages.collect { messages ->
                    allMessages = messages
                    applyFilters()
                }
            }
        }
    }

    private fun applyFilters() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val deletionOnly = deletionOnlySwitch.isChecked

        val visible = allMessages.filter { message ->
            val matchesDeletion = !deletionOnly || message.isDeletionMarker
            val matchesQuery = query.isBlank() || listOf(
                message.sender, message.conversation, message.content, message.app
            ).any { it.contains(query, ignoreCase = true) }
            matchesDeletion && matchesQuery
        }

        adapter.submitList(visible)
        countView.text = getString(R.string.capture_count, allMessages.size)
        deletedCountView.text = getString(
            R.string.deleted_capture_count,
            allMessages.count { it.isDeletionMarker }
        )

        val hasMessages = visible.isNotEmpty()
        recyclerView.visibility = if (hasMessages) View.VISIBLE else View.GONE
        emptyState.visibility = if (hasMessages) View.GONE else View.VISIBLE
        emptyState.text = if (allMessages.isEmpty()) {
            getString(R.string.empty_state)
        } else {
            getString(R.string.empty_filtered_state)
        }
    }

    private fun showMessageDetails(message: Message) {
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val status = if (message.isDeletionMarker) {
            getString(R.string.deletion_marker)
        } else {
            getString(R.string.saved_before_deletion)
        }
        val details = buildString {
            appendLine("${getString(R.string.label_sender)}: ${message.sender}")
            appendLine("${getString(R.string.label_conversation)}: ${message.conversation}")
            appendLine("${getString(R.string.label_app)}: ${message.app}")
            appendLine("${getString(R.string.label_status)}: $status")
            appendLine("${getString(R.string.label_time)}: ${formatter.format(Date(message.timestamp))}")
            appendLine()
            appendLine(message.content)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.details_title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
