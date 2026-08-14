package com.notiftracker

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notiftracker.data.MediaEntity
import com.notiftracker.data.MediaType
import com.notiftracker.data.TrackerRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var adapter: AudioAdapter
    private lateinit var repository: TrackerRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

        supportActionBar?.title = getString(R.string.audio_library_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repository = TrackerRepository.get(this)

        recyclerView = findViewById(R.id.audioRecyclerView)
        emptyState = findViewById(R.id.tvAudioEmpty)

        adapter = AudioAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // La liste se met a jour automatiquement (Flow).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.mediaOfType(MediaType.AUDIO).collect { audios ->
                    adapter.submitList(audios)
                    val empty = audios.isEmpty()
                    recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                    emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onPause() {
        super.onPause()
        adapter.releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.releasePlayer()
    }
}

class AudioAdapter : RecyclerView.Adapter<AudioAdapter.ViewHolder>() {

    private var items = listOf<MediaEntity>()
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingPosition = -1

    fun submitList(list: List<MediaEntity>) {
        items = list
        notifyDataSetChanged()
    }

    fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentPlayingPosition = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvAudioName)
        private val tvDate: TextView = view.findViewById(R.id.tvAudioDate)
        private val tvSize: TextView = view.findViewById(R.id.tvAudioSize)
        private val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)

        fun bind(item: MediaEntity, position: Int) {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val label = item.sender ?: item.conversation
            tvName.text = if (label.isNullOrBlank()) {
                "🎤 " + btnPlay.context.getString(R.string.audio_item_default, position + 1)
            } else {
                "🎤 $label"
            }
            tvDate.text = sdf.format(Date(item.timestamp))
            tvSize.text = formatSize(item.sizeBytes)

            val isPlaying = currentPlayingPosition == position
            btnPlay.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )

            btnPlay.setOnClickListener {
                if (isPlaying) stopAudio() else playAudio(item, position)
            }
        }

        private fun playAudio(item: MediaEntity, position: Int) {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null

                val previousPosition = currentPlayingPosition
                currentPlayingPosition = position
                notifyItemChanged(previousPosition)
                notifyItemChanged(position)

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(File(item.localPath).absolutePath)
                    setOnCompletionListener {
                        currentPlayingPosition = -1
                        notifyItemChanged(position)
                    }
                    setOnErrorListener { _, what, extra ->
                        currentPlayingPosition = -1
                        notifyItemChanged(position)
                        Toast.makeText(
                            btnPlay.context,
                            btnPlay.context.getString(R.string.audio_play_error, "$what/$extra"),
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                currentPlayingPosition = -1
                notifyItemChanged(position)
                Toast.makeText(
                    btnPlay.context,
                    btnPlay.context.getString(R.string.audio_play_error, e.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun stopAudio() {
            val previous = currentPlayingPosition
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            currentPlayingPosition = -1
            notifyItemChanged(previous)
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> "${bytes / (1024 * 1024)} MB"
            }
        }
    }
}
