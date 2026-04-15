package com.notiftracker

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val onMessageClick: (Message) -> Unit
) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private var messages = listOf<Message>()

    fun submitList(list: List<Message>) {
        messages = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return ViewHolder(view, onMessageClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    class ViewHolder(
        view: View,
        private val onMessageClick: (Message) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val tvSender: TextView = view.findViewById(R.id.tvSender)
        private val tvConversation: TextView = view.findViewById(R.id.tvConversation)
        private val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvApp: TextView = view.findViewById(R.id.tvApp)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)

        fun bind(msg: Message) {
            tvSender.text = msg.sender
            tvConversation.text = msg.conversation
            tvMessage.text = msg.content
            tvApp.text = msg.app
            tvStatus.text = if (msg.isDeletionMarker) {
                itemView.context.getString(R.string.deletion_marker)
            } else {
                itemView.context.getString(R.string.saved_before_deletion)
            }

            tvStatus.setTypeface(null, if (msg.isDeletionMarker) Typeface.BOLD else Typeface.NORMAL)

            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            tvTime.text = formatter.format(Date(msg.timestamp))

            itemView.setOnClickListener {
                onMessageClick(msg)
            }
        }
    }
}
