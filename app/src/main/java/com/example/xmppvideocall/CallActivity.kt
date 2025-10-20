package com.example.xmppvideocall

import android.media.AudioManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.util.UUID

class CallActivity : AppCompatActivity() {

    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var tvCallStatus: TextView
    private lateinit var btnMute: ImageButton
    private lateinit var btnEndCall: ImageButton
    private lateinit var btnSwitchCamera: ImageButton

    private lateinit var webRtcManager: WebRtcManager
    private lateinit var xmppManager: XmppConnectionManager

    private var calleeJid: String = ""
    private var sessionId: String = ""
    private var hasVideo: Boolean = false
    private var isIncoming: Boolean = false
    private var isMuted: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        // Set audio mode for call
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = hasVideo

        initViews()
        extractIntentData()
        initializeManagers()
        startCall()
    }

    private fun initViews() {
        remoteVideoView = findViewById(R.id.remoteVideoView)
        localVideoView = findViewById(R.id.localVideoView)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        btnMute = findViewById(R.id.btnMute)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)

        btnMute.setOnClickListener { toggleMute() }
        btnEndCall.setOnClickListener { endCall() }
        btnSwitchCamera.setOnClickListener { switchCamera() }

        if (!hasVideo) {
            localVideoView.visibility = android.view.View.GONE
            remoteVideoView.visibility = android.view.View.GONE
            btnSwitchCamera.isEnabled = false
        }
    }

    private fun extractIntentData() {
        calleeJid = intent.getStringExtra(MainActivity.EXTRA_CALLEE_JID) ?: ""
        sessionId = intent.getStringExtra(MainActivity.EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
        hasVideo = intent.getBooleanExtra(MainActivity.EXTRA_HAS_VIDEO, false)
        isIncoming = intent.getBooleanExtra(MainActivity.EXTRA_IS_INCOMING, false)
    }

    private fun initializeManagers() {
        webRtcManager = WebRtcManager(this)
        xmppManager = XmppConnectionManager() // Get singleton or shared instance in real app

        setupWebRtcCallbacks()
        setupXmppCallbacks()
    }

    private fun setupWebRtcCallbacks() {
        webRtcManager.onIceCandidate = { candidate ->
            xmppManager.sendIceCandidate(
                calleeJid,
                sessionId,
                candidate.sdp,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }

        webRtcManager.onAddStream = { stream ->
            runOnUiThread {
                if (stream.videoTracks.size > 0 && hasVideo) {
                    val remoteVideoTrack = stream.videoTracks[0]
                    remoteVideoTrack.addSink(remoteVideoView)
                }
                tvCallStatus.text = "Connected"
            }
        }

        webRtcManager.onIceConnectionChange = { state ->
            runOnUiThread {
                when (state) {
                    PeerConnection.IceConnectionState.CHECKING -> tvCallStatus.text = "Connecting..."
                    PeerConnection.IceConnectionState.CONNECTED -> tvCallStatus.text = "Connected"
                    PeerConnection.IceConnectionState.COMPLETED -> tvCallStatus.text = "Connected"
                    PeerConnection.IceConnectionState.FAILED -> {
                        tvCallStatus.text = "Connection Failed"
                        Toast.makeText(this, "Call failed", Toast.LENGTH_SHORT).show()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        tvCallStatus.text = "Disconnected"
                        finish()
                    }
                    PeerConnection.IceConnectionState.CLOSED -> finish()
                    else -> {}
                }
            }
        }
    }

    private fun setupXmppCallbacks() {
        xmppManager.onCallAccepted = { sid, remoteSdp ->
            if (sid == sessionId) {
                runOnUiThread {
                    webRtcManager.setRemoteDescription(remoteSdp, SessionDescription.Type.ANSWER)
                    tvCallStatus.text = "Call Accepted"
                }
            }
        }

        xmppManager.onIceCandidate = { sid, sdpMid, candidateSdp, sdpMLineIndex, _ ->
            if (sid == sessionId) {
                runOnUiThread {
                    webRtcManager.addIceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
                }
            }
        }

        xmppManager.onCallTerminated = { sid ->
            if (sid == sessionId) {
                runOnUiThread {
                    Toast.makeText(this, "Call ended by remote", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun startCall() {
        webRtcManager.initialize()
        webRtcManager.createPeerConnection()

        if (hasVideo) {
            webRtcManager.startLocalMedia(true, localVideoView)
        } else {
            webRtcManager.startLocalMedia(false, null)
        }

        if (isIncoming) {
            handleIncomingCall()
        } else {
            handleOutgoingCall()
        }
    }

    private fun handleOutgoingCall() {
        tvCallStatus.text = "Calling..."

        webRtcManager.createOffer { sdp ->
            runOnUiThread {
                xmppManager.sendSessionInitiate(calleeJid, sessionId, sdp.description, hasVideo)
            }
        }
    }

    private fun handleIncomingCall() {
        tvCallStatus.text = "Accepting call..."

        // Get remote SDP from intent or wait for it via XMPP callback
        val remoteSdp = intent.getStringExtra(MainActivity.EXTRA_REMOTE_SDP)
        if (remoteSdp != null) {
            webRtcManager.setRemoteDescription(remoteSdp, SessionDescription.Type.OFFER)
        }

        webRtcManager.createAnswer { sdp ->
            runOnUiThread {
                xmppManager.sendSessionAccept(calleeJid, sessionId, sdp.description)
            }
        }
    }

    private fun toggleMute() {
        isMuted = !webRtcManager.toggleMute()
        btnMute.alpha = if (isMuted) 0.5f else 1.0f
        Toast.makeText(this, if (isMuted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
    }

    private fun switchCamera() {
        if (hasVideo) {
            webRtcManager.switchCamera()
        }
    }

    private fun endCall() {
        xmppManager.sendSessionTerminate(calleeJid, sessionId)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcManager.release()

        // Reset audio mode
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
