package com.example.xmppvideocall

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.xmppvideocall.JingleCallManager
import com.example.xmppvideocall.JingleSession
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.jxmpp.jid.impl.JidCreate
import android.widget.TextView
import org.jxmpp.jid.Jid

class HomeActivity : AppCompatActivity() {

    private lateinit var audioCallCard: MaterialCardView
    private lateinit var videoCallCard: MaterialCardView
    private lateinit var logoutButton: MaterialButton
    private lateinit var userJidText: TextView
    private lateinit var connectionStatusText: TextView

    private lateinit var jingleManager: JingleCallManager
    private var currentSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        initViews()
        setupUI()
        setupJingleManager()
    }

    private fun initViews() {
        audioCallCard = findViewById(R.id.audioCallCard)
        videoCallCard = findViewById(R.id.videoCallCard)
        logoutButton = findViewById(R.id.logoutButton)
        userJidText = findViewById(R.id.userJidText)
        connectionStatusText = findViewById(R.id.connectionStatusText)
    }

    private fun setupUI() {
        val username = intent.getStringExtra("USERNAME") ?: "User"
        val server = "https://ejabberd.arafinahmed.com/"
        userJidText.text = "$username@$server"

        audioCallCard.setOnClickListener {
            showMakeCallDialog(audioOnly = true)
        }

        videoCallCard.setOnClickListener {
            showMakeCallDialog(audioOnly = false)
        }

        logoutButton.setOnClickListener {
            MainActivity.xmppConnectionManager?.disconnect()
            finish()
        }
    }

    private fun setupJingleManager() {
        val connection = MainActivity.xmppConnectionManager?.getConnection()

        if (connection == null) {
            Toast.makeText(this, "Not connected to XMPP", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        jingleManager = JingleCallManager(
            connection,
            XmppApplication.instance.webRtcManager
        )

        jingleManager.onIncomingCall = { sessionId, from, hasVideo ->
            currentSessionId = sessionId
            showIncomingCallDialog(sessionId, from.toString(), hasVideo)
        }

        jingleManager.onCallEnded = {
            currentSessionId = null
        }
    }

    private fun showMakeCallDialog(audioOnly: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_make_call)

        val titleText = dialog.findViewById<TextView>(R.id.titleText)
        val callTypeText = dialog.findViewById<TextView>(R.id.callTypeText)
        val recipientJidInput = dialog.findViewById<TextInputEditText>(R.id.recipientJidInput)
        val cancelButton = dialog.findViewById<MaterialButton>(R.id.cancelButton)
        val callButton = dialog.findViewById<MaterialButton>(R.id.callButton)

        callTypeText.text = if (audioOnly) "Audio Call" else "Video Call"

        // Pre-fill with example JID
        recipientJidInput.setText("rafin@ejabberd.arafinahmed.com")

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        callButton.setOnClickListener {
            val recipientJid = recipientJidInput.text.toString()

            if (recipientJid.isEmpty()) {
                Toast.makeText(this, "Please enter recipient JID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            initiateCall(recipientJid, audioOnly)
        }

        dialog.show()
    }

    private fun initiateCall(recipientJid: String, audioOnly: Boolean) {
        try {
            val jid = JidCreate.from(recipientJid)
            val session = jingleManager.initiateCall(jid, true, !audioOnly)
            currentSessionId = session.sessionId

            val intent = Intent(this, CallActivity::class.java)
            intent.putExtra("SESSION_ID", session.sessionId)
            intent.putExtra("RECIPIENT_JID", recipientJid)
            intent.putExtra("AUDIO_ONLY", audioOnly)
            intent.putExtra("IS_INCOMING", false)
            startActivity(intent)

        } catch (e: Exception) {
            Toast.makeText(this, "Invalid JID format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showIncomingCallDialog(sessionId: String, callerJid: String, hasVideo: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_incoming_call)
        dialog.setCancelable(false)

        val callerName = dialog.findViewById<TextView>(R.id.callerName)
        val callTypeText = dialog.findViewById<TextView>(R.id.callTypeText)
        val declineButton = dialog.findViewById<MaterialButton>(R.id.declineButton)
        val acceptButton = dialog.findViewById<MaterialButton>(R.id.acceptButton)

        callerName.text = callerJid
        callTypeText.text = if (hasVideo) "Video Call" else "Audio Call"

        declineButton.setOnClickListener {
            dialog.dismiss()
            jingleManager.terminateSession(sessionId)
        }

        acceptButton.setOnClickListener {
            dialog.dismiss()
            acceptCall(sessionId, callerJid, hasVideo)
        }

        dialog.show()
    }

    private fun acceptCall(sessionId: String, callerJid: String, hasVideo: Boolean) {
        val intent = Intent(this, CallActivity::class.java)
        intent.putExtra("SESSION_ID", sessionId)
        intent.putExtra("RECIPIENT_JID", callerJid)
        intent.putExtra("AUDIO_ONLY", !hasVideo)
        intent.putExtra("IS_INCOMING", true)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            jingleManager.cleanup()
        }
    }
}
