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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var adapter: AudioAdapter
    private lateinit var audioObserver: AudioObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

        supportActionBar?.title = "Audios sauvegardés"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        audioObserver = AudioObserver(this)

        recyclerView = findViewById(R.id.audioRecyclerView)
        emptyState = findViewById(R.id.tvAudioEmpty)

        adapter = AudioAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<MaterialButton>(R.id.btnRefreshAudio).setOnClickListener {
            loadAudios()
        }

        loadAudios()
    }

    private fun loadAudios() {
        val files = audioObserver.getSavedAudios()

        if (files.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            adapter.submitList(files)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onPause() {
        super.onPause()
        // Libere le lecteur quand l'ecran passe en arriere-plan (evite les fuites).
        adapter.releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.releasePlayer()
    }
}

class AudioAdapter : RecyclerView.Adapter<AudioAdapter.ViewHolder>() {

    private var files = listOf<File>()
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingPosition = -1

    fun submitList(list: List<File>) {
        files = list
        notifyDataSetChanged()
    }

    fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position], position)
    }

    override fun getItemCount() = files.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvAudioName)
        private val tvDate: TextView = view.findViewById(R.id.tvAudioDate)
        private val tvSize: TextView = view.findViewById(R.id.tvAudioSize)
        private val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)

        fun bind(file: File, position: Int) {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            tvName.text = "🎤 Audio ${position + 1}"
            tvDate.text = sdf.format(Date(file.lastModified()))
            tvSize.text = formatSize(file.length())

            val isPlaying = currentPlayingPosition == position
            btnPlay.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )

            btnPlay.setOnClickListener {
                if (isPlaying) {
                    stopAudio()
                } else {
                    playAudio(file, position)
                }
            }
        }

        private fun playAudio(file: File, position: Int) {
            try {
                // Arrêter l'audio en cours
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null

                val previousPosition = currentPlayingPosition
                currentPlayingPosition = position

                // Notifier les changements visuels
                notifyItemChanged(previousPosition)
                notifyItemChanged(position)

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
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