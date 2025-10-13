package com.example.xmppvideocall

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.xmppvideocall.databinding.ActivityContactsBinding
import com.example.xmppvideocall.Contact
import com.example.xmppvideocall.XmppConnectionManager
import org.jxmpp.jid.Jid

class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private val contactsAdapter = ContactsAdapter()
    private var xmppManager: XmppConnectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupXmppManager()
    }

    private fun setupRecyclerView() {
        binding.contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ContactsActivity)
            adapter = contactsAdapter
        }

        contactsAdapter.onAudioCallClick = { contact ->
            startCall(contact.jid, audioOnly = true)
        }

        contactsAdapter.onVideoCallClick = { contact ->
            startCall(contact.jid, audioOnly = false)
        }
    }

    private fun setupXmppManager() {
        xmppManager = MainActivity.xmppConnectionManager

        xmppManager?.onContactsUpdated = { contacts ->
            runOnUiThread {
                if (contacts.isEmpty()) {
                    binding.emptyText.visibility = View.VISIBLE
                    binding.contactsRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyText.visibility = View.GONE
                    binding.contactsRecyclerView.visibility = View.VISIBLE
                    contactsAdapter.submitList(contacts)
                }
            }
        }
    }

    private fun startCall(recipientJid: Jid, audioOnly: Boolean) {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("RECIPIENT_JID", recipientJid.toString())
            putExtra("AUDIO_ONLY", audioOnly)
            putExtra("IS_INCOMING", false)
        }
        startActivity(intent)
    }
}

class ContactsAdapter : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    private var contacts = emptyList<Contact>()
    var onAudioCallClick: ((Contact) -> Unit)? = null
    var onVideoCallClick: ((Contact) -> Unit)? = null

    fun submitList(newContacts: List<Contact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(contacts[position])
    }

    override fun getItemCount() = contacts.size

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.contactName)
        private val statusText: TextView = itemView.findViewById(R.id.contactStatus)
        private val audioButton: ImageButton = itemView.findViewById(R.id.audioCallButton)
        private val videoButton: ImageButton = itemView.findViewById(R.id.videoCallButton)

        fun bind(contact: Contact) {
            nameText.text = contact.name
            statusText.text = contact.status

            audioButton.setOnClickListener {
                onAudioCallClick?.invoke(contact)
            }

            videoButton.setOnClickListener {
                onVideoCallClick?.invoke(contact)
            }
        }
    }
}
