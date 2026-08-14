package com.notiftracker.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.ChipGroup
import com.notiftracker.R
import com.notiftracker.data.MediaEntity
import com.notiftracker.data.MediaType
import com.notiftracker.data.TrackerRepository
import kotlinx.coroutines.launch
import java.io.File

/** Onglet Médias : photos, vidéos et vocaux, filtrables par type. */
class MediaFragment : Fragment(R.layout.fragment_media) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var adapter: MediaAdapter
    private lateinit var repository: TrackerRepository

    private var allMedia: List<MediaEntity> = emptyList()
    private var currentFilter: MediaType? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TrackerRepository.get(requireContext())

        recyclerView = view.findViewById(R.id.mediaRecyclerView)
        emptyState = view.findViewById(R.id.tvMediaEmpty)
        chipGroup = view.findViewById(R.id.chipGroupFilter)

        adapter = MediaAdapter(onOpenPhoto = ::openPhoto, onOpenVideo = ::openVideo)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipPhoto -> MediaType.PHOTO
                R.id.chipVideo -> MediaType.VIDEO
                R.id.chipAudio -> MediaType.AUDIO
                else -> null
            }
            render()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.media.collect { media ->
                    allMedia = media
                    render()
                }
            }
        }
    }

    private fun render() {
        val filtered = currentFilter?.let { type -> allMedia.filter { it.type == type } } ?: allMedia
        adapter.submitList(filtered)
        val empty = filtered.isEmpty()
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun openPhoto(item: MediaEntity) {
        val imageView = ImageView(requireContext()).apply {
            adjustViewBounds = true
            load(File(item.localPath))
        }
        AlertDialog.Builder(requireContext())
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openVideo(item: MediaEntity) {
        val dialog = Dialog(requireContext())
        val videoView = VideoView(requireContext())
        val controller = MediaController(requireContext())
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setVideoPath(item.localPath)
        videoView.setOnPreparedListener { it.start() }
        dialog.setContentView(
            videoView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.setOnDismissListener { videoView.stopPlayback() }
        dialog.show()
    }

    override fun onPause() {
        super.onPause()
        adapter.releasePlayer()
    }
}
