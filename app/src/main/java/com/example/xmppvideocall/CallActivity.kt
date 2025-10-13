package com.example.xmppvideocall
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.xmppvideocall.JingleSession
import com.example.xmppvideocall.CallState
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.webrtc.SurfaceViewRenderer
import android.widget.TextView

class CallActivity : AppCompatActivity() {

    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var callStatusText: TextView
    private lateinit var contactNameText: TextView
    private lateinit var toggleAudioButton: FloatingActionButton
    private lateinit var toggleVideoButton: FloatingActionButton
    private lateinit var switchCameraButton: FloatingActionButton
    private lateinit var endCallButton: FloatingActionButton

    private var currentSession: JingleSession? = null
    private var isAudioMuted = false
    private var isVideoOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        setupVideoViews()
        setupUI()
        handleIntent()
    }

    private fun initViews() {
        remoteVideoView = findViewById(R.id.remoteVideoView)
        localVideoView = findViewById(R.id.localVideoView)
        callStatusText = findViewById(R.id.callStatusText)
        contactNameText = findViewById(R.id.contactNameText)
        toggleAudioButton = findViewById(R.id.toggleAudioButton)
        toggleVideoButton = findViewById(R.id.toggleVideoButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        endCallButton = findViewById(R.id.endCallButton)
    }

    private fun setupVideoViews() {
        val eglBase = XmppApplication.instance.webRtcManager.eglBase
        localVideoView.init(eglBase.eglBaseContext, null)
        remoteVideoView.init(eglBase.eglBaseContext, null)

        localVideoView.setZOrderMediaOverlay(true)
        localVideoView.setEnableHardwareScaler(true)
        remoteVideoView.setEnableHardwareScaler(true)
    }

    private fun setupUI() {
        toggleAudioButton.setOnClickListener {
            isAudioMuted = currentSession?.toggleAudio() == false
            updateAudioButton()
        }

        toggleVideoButton.setOnClickListener {
            isVideoOff = currentSession?.toggleVideo() == false
            updateVideoButton()
            localVideoView.visibility = if (isVideoOff) View.GONE else View.VISIBLE
        }

        switchCameraButton.setOnClickListener {
            currentSession?.switchCamera()
        }

        endCallButton.setOnClickListener {
            endCall()
        }
    }

    private fun handleIntent() {
        val sessionId = intent.getStringExtra("SESSION_ID")
        val recipientJid = intent.getStringExtra("RECIPIENT_JID")
        val audioOnly = intent.getBooleanExtra("AUDIO_ONLY", false)
        val isIncoming = intent.getBooleanExtra("IS_INCOMING", false)

        contactNameText.text = recipientJid ?: "Unknown"

        if (sessionId != null) {
            val jingleManager = (application as XmppApplication).let {
                val connection = MainActivity.xmppConnectionManager?.getConnection()
                if (connection != null) {
                  JingleCallManager(
                        connection,
                        it.webRtcManager
                    )
                } else null
            }

            if (jingleManager != null) {
                if (isIncoming) {
                    currentSession = jingleManager.acceptCall(sessionId, true, !audioOnly)
                } else {
                    currentSession = jingleManager.getSession(sessionId)
                }

                currentSession?.let { setupSessionCallbacks(it) }
                currentSession?.getLocalVideoTrack()?.addSink(localVideoView)

                if (audioOnly) {
                    localVideoView.visibility = View.GONE
                    switchCameraButton.visibility = View.GONE
                    toggleVideoButton.visibility = View.GONE
                }
            }
        }
    }

    private fun setupSessionCallbacks(session: JingleSession) {
        session.onRemoteStreamAdded = { stream ->
            runOnUiThread {
                val videoTrack = stream.videoTracks.firstOrNull()
                videoTrack?.addSink(remoteVideoView)
            }
        }

        session.onCallStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    CallState.INITIATING -> {
                        callStatusText.text = getString(R.string.calling)
                    }
                    CallState.RINGING -> {
                        callStatusText.text = getString(R.string.ringing)
                    }
                    CallState.CONNECTING -> {
                        callStatusText.text = "Connecting…"
                    }
                    CallState.CONNECTED -> {
                        callStatusText.text = getString(R.string.connected_call)
                        callStatusText.postDelayed({
                            callStatusText.visibility = View.GONE
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

    private fun endCall() {
        currentSession?.terminate()
        finish()
    }

    private fun updateAudioButton() {
        toggleAudioButton.alpha = if (isAudioMuted) 0.5f else 1.0f
    }

    private fun updateVideoButton() {
        toggleVideoButton.alpha = if (isVideoOff) 0.5f else 1.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        localVideoView.release()
        remoteVideoView.release()
        currentSession?.close()
    }
}
