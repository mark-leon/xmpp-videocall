package com.example.xmppvideocall

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.xmppvideocall.databinding.ActivityCallBinding
import com.example.xmppvideocall.databinding.DialogIncomingCallBinding

import org.jxmpp.jid.impl.JidCreate
import org.webrtc.MediaStream

class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var jingleManager: JingleCallManager
    private var currentSession: JingleSession? = null
    private var isAudioMuted = false
    private var isVideoOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupVideoViews()
        setupJingleManager()
        setupUI()
        handleIntent()
    }

    private fun setupVideoViews() {
        val eglBase = XmppApplication.instance.webRtcManager.eglBase
        binding.localVideoView.init(eglBase.eglBaseContext, null)
        binding.remoteVideoView.init(eglBase.eglBaseContext, null)

        binding.localVideoView.setZOrderMediaOverlay(true)
        binding.localVideoView.setEnableHardwareScaler(true)
        binding.remoteVideoView.setEnableHardwareScaler(true)
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
            showIncomingCallDialog(sessionId, from.toString(), hasVideo)
        }

        jingleManager.onCallEnded = {
            finish()
        }
    }

    private fun setupUI() {
        binding.toggleAudioButton.setOnClickListener {
            isAudioMuted = currentSession?.toggleAudio() == false
            updateAudioButton()
        }

        binding.toggleVideoButton.setOnClickListener {
            isVideoOff = currentSession?.toggleVideo() == false
            updateVideoButton()
            binding.localVideoView.visibility = if (isVideoOff) View.GONE else View.VISIBLE
        }

        binding.switchCameraButton.setOnClickListener {
            currentSession?.switchCamera()
        }

        binding.endCallButton.setOnClickListener {
            endCall()
        }
    }

    private fun handleIntent() {
        val recipientJid = intent.getStringExtra("RECIPIENT_JID")
        val audioOnly = intent.getBooleanExtra("AUDIO_ONLY", false)
        val isIncoming = intent.getBooleanExtra("IS_INCOMING", false)

        if (!isIncoming && recipientJid != null) {
            // Outgoing call
            initiateCall(recipientJid, !audioOnly)
        }
    }

    private fun initiateCall(recipientJid: String, videoEnabled: Boolean) {
        try {
            val jid = JidCreate.from(recipientJid)
            binding.contactNameText.text = jid.localpartOrNull?.toString() ?: recipientJid
            binding.callStatusText.text = getString(R.string.calling)

            currentSession = jingleManager.initiateCall(jid, true, videoEnabled)
            setupSessionCallbacks(currentSession!!)

            // Show local video
            currentSession?.getLocalVideoTrack()?.addSink(binding.localVideoView)

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to initiate call", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupSessionCallbacks(session: JingleSession) {
        session.onRemoteStreamAdded = { stream ->
            runOnUiThread {
                val videoTrack = stream.videoTracks.firstOrNull()
                videoTrack?.addSink(binding.remoteVideoView)
            }
        }

        session.onCallStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    CallState.INITIATING -> {
                        binding.callStatusText.text = getString(R.string.calling)
                    }
                    CallState.RINGING -> {
                        binding.callStatusText.text = getString(R.string.ringing)
                    }
                    CallState.CONNECTING -> {
                        binding.callStatusText.text = "Connecting…"
                    }
                    CallState.CONNECTED -> {
                        binding.callStatusText.text = getString(R.string.connected_call)
                        binding.callStatusText.postDelayed({
                            binding.callStatusText.visibility = View.GONE
                        }, 2000)
                    }
                    CallState.ENDED -> {
                        finish()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showIncomingCallDialog(sessionId: String, callerJid: String, hasVideo: Boolean) {
        val dialogBinding = DialogIncomingCallBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(false)

        dialogBinding.callerName.text = callerJid
        dialogBinding.callTypeText.text = if (hasVideo) "Video Call" else "Audio Call"

        dialogBinding.acceptButton.setOnClickListener {
            dialog.dismiss()
            acceptCall(sessionId, hasVideo)
        }

        dialogBinding.declineButton.setOnClickListener {
            dialog.dismiss()
            jingleManager.terminateSession(sessionId)
            finish()
        }

        dialog.show()
    }

    private fun acceptCall(sessionId: String, videoEnabled: Boolean) {
        currentSession = jingleManager.acceptCall(sessionId, true, videoEnabled)

        if (currentSession != null) {
            setupSessionCallbacks(currentSession!!)
            binding.callStatusText.text = "Connecting…"

            // Show local video
            currentSession?.getLocalVideoTrack()?.addSink(binding.localVideoView)
        } else {
            Toast.makeText(this, "Failed to accept call", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun endCall() {
        currentSession?.let {
            jingleManager.terminateSession(it.sessionId)
        }
        finish()
    }

    private fun updateAudioButton() {
        binding.toggleAudioButton.alpha = if (isAudioMuted) 0.5f else 1.0f
    }

    private fun updateVideoButton() {
        binding.toggleVideoButton.alpha = if (isVideoOff) 0.5f else 1.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.localVideoView.release()
        binding.remoteVideoView.release()
        currentSession?.close()
    }
}
