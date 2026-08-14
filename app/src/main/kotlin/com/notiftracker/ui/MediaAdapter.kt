package com.notiftracker.ui

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.load
import com.notiftracker.R
import com.notiftracker.data.MediaEntity
import com.notiftracker.data.MediaType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Liste des médias : vignette pour photos/vidéos (Coil), lecteur audio inline.
 * Les photos/vidéos délèguent l'ouverture plein écran au fragment.
 */
class MediaAdapter(
    private val onOpenPhoto: (MediaEntity) -> Unit,
    private val onOpenVideo: (MediaEntity) -> Unit
) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

    private var items = listOf<MediaEntity>()
    private var imageLoader: ImageLoader? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playingPosition = -1

    fun submitList(list: List<MediaEntity>) {
        items = list
        notifyDataSetChanged()
    }

    fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        playingPosition = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (imageLoader == null) {
            imageLoader = ImageLoader.Builder(parent.context)
                .components { add(VideoFrameDecoder.Factory()) }
                .crossfade(true)
                .build()
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(items[position], position)

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.imgThumb)
        private val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)
        private val tvTitle: TextView = view.findViewById(R.id.tvMediaTitle)
        private val tvSubtitle: TextView = view.findViewById(R.id.tvMediaSubtitle)
        private val tvMeta: TextView = view.findViewById(R.id.tvMediaMeta)

        fun bind(item: MediaEntity, position: Int) {
            val ctx = itemView.context
            val label = item.sender ?: item.conversation
            tvTitle.text = if (label.isNullOrBlank()) {
                ctx.getString(R.string.media_unknown_sender)
            } else {
                label
            }
            val typeLabel = when (item.type) {
                MediaType.AUDIO -> ctx.getString(R.string.media_type_audio)
                MediaType.PHOTO -> ctx.getString(R.string.media_type_photo)
                MediaType.VIDEO -> ctx.getString(R.string.media_type_video)
            }
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(Date(item.timestamp))
            tvSubtitle.text = "$typeLabel · $date"
            tvMeta.text = formatSize(item.sizeBytes)

            val file = File(item.localPath)
            when (item.type) {
                MediaType.AUDIO -> bindAudio(item, position)
                MediaType.PHOTO -> bindPhoto(item, file)
                MediaType.VIDEO -> bindVideo(item, file)
            }
        }

        private fun bindAudio(item: MediaEntity, position: Int) {
            thumb.setImageDrawable(null)
            btnPlay.visibility = View.VISIBLE
            val isPlaying = playingPosition == position
            btnPlay.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            itemView.setOnClickListener(null)
            btnPlay.setOnClickListener {
                if (isPlaying) stopAudio() else playAudio(item, position)
            }
        }

        private fun bindPhoto(item: MediaEntity, file: File) {
            btnPlay.visibility = View.GONE
            thumb.load(file, imageLoader!!)
            val open = View.OnClickListener { onOpenPhoto(item) }
            thumb.setOnClickListener(open)
            itemView.setOnClickListener(open)
        }

        private fun bindVideo(item: MediaEntity, file: File) {
            btnPlay.visibility = View.VISIBLE
            btnPlay.setImageResource(android.R.drawable.ic_media_play)
            thumb.load(file, imageLoader!!)
            val open = View.OnClickListener { onOpenVideo(item) }
            btnPlay.setOnClickListener(open)
            itemView.setOnClickListener(open)
        }

        private fun playAudio(item: MediaEntity, position: Int) {
            try {
                mediaPlayer?.release()
                mediaPlayer = null
                val previous = playingPosition
                playingPosition = position
                notifyItemChanged(previous)
                notifyItemChanged(position)

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(item.localPath)
                    setOnCompletionListener {
                        playingPosition = -1
                        notifyItemChanged(position)
                    }
                    setOnErrorListener { _, what, extra ->
                        playingPosition = -1
                        notifyItemChanged(position)
                        Toast.makeText(
                            itemView.context,
                            itemView.context.getString(R.string.audio_play_error, "$what/$extra"),
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                playingPosition = -1
                notifyItemChanged(position)
                Toast.makeText(
                    itemView.context,
                    itemView.context.getString(R.string.audio_play_error, e.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun stopAudio() {
            val previous = playingPosition
            mediaPlayer?.release()
            mediaPlayer = null
            playingPosition = -1
            notifyItemChanged(previous)
        }

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
