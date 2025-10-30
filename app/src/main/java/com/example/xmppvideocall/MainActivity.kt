package com.example.xmppvideocall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.xmppvideocall.databinding.ActivityMainBinding
import org.jivesoftware.smack.packet.Message
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var xmppManager: XmppConnectionManager? = null
    private var webRTCManager: WebRTCCallManager? = null
    private var currentCallId: String? = null
    private var currentPeerJid: String? = null
    private var callStartTime: Long = 0
    private var callDurationHandler: Handler? = null
    private var callDurationRunnable: Runnable? = null
    private var isMuted = false
    private var isSpeakerOn = false
    private var isVideoCall = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupUI()
        setupJingleHandler()
        initializeWebRTC()
    }

    private fun initializeWebRTC() {
        webRTCManager = WebRTCCallManager(this)
        webRTCManager?.setRTCListener(object : WebRTCCallManager.RTCListener {
            override fun onLocalSdpCreated(sdp: String, type: String) {
                runOnUiThread {
                    binding.tvStatus.text = "SDP Created: $type"
                    binding.tvSdpInfo.text = sdp

                    // Send SDP via XMPP Jingle
                    currentCallId?.let { sessionId ->
                        currentPeerJid?.let { peerJid ->
                            if (type == "offer") {
                                xmppManager?.sendSessionInitiate(peerJid, sessionId, sdp)
                            } else if (type == "answer") {
                                xmppManager?.sendSessionAccept(peerJid, sessionId, sdp)
                            }
                        }
                    }
                }
            }

            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                runOnUiThread {
                    val candidateInfo = "Candidate: $candidate\nMID: $sdpMid\nIndex: $sdpMLineIndex"
                    binding.tvIceCandidates.text = candidateInfo

                    // Parse WebRTC candidate string to Candidate object
                    currentCallId?.let { sessionId ->
                        currentPeerJid?.let { peerJid ->
                            val jingleCandidate = parseWebRTCCandidate(candidate)
                            xmppManager?.sendIceCandidate(peerJid, sessionId, jingleCandidate)
                        }
                    }
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                runOnUiThread {
                    binding.tvStatus.text = "ICE State: $state"
                }
            }

            override fun onCallConnected() {
                runOnUiThread {
                    binding.tvStatus.text = "Call Connected via WebRTC"
                    Toast.makeText(this@MainActivity, "WebRTC connection established", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCallDisconnected() {
                runOnUiThread {
                    binding.tvStatus.text = "WebRTC Disconnected"
                }
            }

            override fun onRemoteVideoTrack(track: VideoTrack) {
                runOnUiThread {
                    // Handle remote video track if needed
                    Toast.makeText(this@MainActivity, "Remote video track received", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun checkPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            connectToXmpp()
        }

        binding.btnDisconnect.setOnClickListener {
            disconnectFromXmpp()
        }

        binding.btnStartCall.setOnClickListener {
            startOutgoingCall()
        }

        binding.btnAnswer.setOnClickListener {
            answerCall()
        }

        binding.btnReject.setOnClickListener {
            rejectCall()
        }

        binding.btnEndCall.setOnClickListener {
            endCall()
        }

        binding.btnMute.setOnClickListener {
            toggleMute()
        }

        binding.btnSpeaker.setOnClickListener {
            toggleSpeaker()
        }

        updateUIState(false)
    }

    private fun setupJingleHandler() {
        JingleMessageHandler.setCallback(object : JingleMessageHandler.JingleCallback {
            override fun onSdpReceived(sdp: String, action: String) {
                runOnUiThread {
                    binding.tvStatus.text = "SDP Received: $action"
                    binding.tvSdpInfo.text = sdp

                    when (action) {
                        "session-initiate" -> {
                            // Incoming call offer
                            webRTCManager?.handleRemoteOffer(sdp)
                            showCallInProgress()
                        }
                        "session-accept" -> {
                            // Call accepted - handle answer
                            webRTCManager?.handleRemoteAnswer(sdp)
                        }
                    }
                }
            }

            override fun onIceCandidatesReceived(candidates: List<Candidate>) {
                runOnUiThread {
                    val candidateInfo = candidates.joinToString("\n") {
                        "IP: ${it.ip}:${it.port} Type: ${it.type}"
                    }
                    binding.tvIceCandidates.text = candidateInfo

                    // Add ICE candidates to WebRTC
                    candidates.forEach { candidate ->
                        // Convert Jingle candidate to WebRTC format
                        val candidateSdp = "candidate:${candidate.foundation} ${candidate.component} " +
                                "${candidate.protocol} ${candidate.priority} ${candidate.ip} " +
                                "${candidate.port} typ ${candidate.type}"

                        webRTCManager?.addIceCandidate(
                            candidateSdp,
                            "0", // Default media ID
                            0
                        )
                    }
                }
            }
        })
    }

    private fun connectToXmpp() {
        val username = binding.etUsername.text.toString()
        val password = binding.etPassword.text.toString()
        val domain = binding.etDomain.text.toString()

        if (username.isEmpty() || password.isEmpty() || domain.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvStatus.text = "Connecting..."

        xmppManager = XmppConnectionManager(username, password, domain)
        xmppManager?.setWebRTCManager(webRTCManager!!)
        xmppManager?.setConnectionListener(object : XmppConnectionManager.ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    binding.tvStatus.text = "Connected - Ready to make calls"
                    updateUIState(true)
                    Toast.makeText(this@MainActivity, "Connected successfully", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    binding.tvStatus.text = "Disconnected"
                    updateUIState(false)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    binding.tvStatus.text = "Error: $error"
                    Toast.makeText(this@MainActivity, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }

            override fun onMessageReceived(message: Message, elementType: String) {
                runOnUiThread {
                    handleIncomingMessage(message, elementType)
                }
            }

            override fun onCallInitiated(sessionId: String) {
                runOnUiThread {
                    currentCallId = sessionId
                    binding.tvStatus.text = "Calling..."

                    // Initialize WebRTC peer connection
                    webRTCManager?.initializePeerConnection()
                    webRTCManager?.startCall(isVideoCall)

                    Toast.makeText(this@MainActivity, "Call initiated", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCallAccepted(sessionId: String) {
                runOnUiThread {
                    binding.tvStatus.text = "Call connected"
                    showCallInProgress()
                    startCallDuration()
                }
            }

            override fun onSdpReceived(sdp: String, type: String, sessionId: String) {
                runOnUiThread {
                    binding.tvStatus.text = "SDP Received: $type"
                    binding.tvSdpInfo.text = sdp

                    // Pass SDP to WebRTC
                    when (type.lowercase()) {
                        "offer" -> webRTCManager?.handleRemoteOffer(sdp)
                        "answer" -> webRTCManager?.handleRemoteAnswer(sdp)
                    }
                }
            }

            override fun onIceCandidateReceived(candidate: Candidate, sessionId: String) {
                runOnUiThread {
                    // Convert Jingle candidate to WebRTC format and add
                    val candidateSdp = SDPJingleConverter.candidateToSdpLine(candidate)
                    webRTCManager?.addIceCandidate(candidateSdp, "0", 0)
                }
            }
        })

        xmppManager?.connect()
    }

    private fun disconnectFromXmpp() {
        xmppManager?.disconnect()
        xmppManager = null
        binding.tvStatus.text = "Disconnected"
        updateUIState(false)
    }

    private fun startOutgoingCall() {
        val recipientJid = binding.etRecipientJid.text.toString().trim()

        if (recipientJid.isEmpty()) {
            Toast.makeText(this, "Please enter recipient JID", Toast.LENGTH_SHORT).show()
            return
        }

        if (!recipientJid.contains("@")) {
            Toast.makeText(this, "Invalid JID format. Use: user@domain.com", Toast.LENGTH_SHORT).show()
            return
        }

        isVideoCall = binding.rbVideoCall.isChecked
        val mediaType = if (binding.rbAudioCall.isChecked) "audio" else "video"

        currentPeerJid = recipientJid
        xmppManager?.initiateCall(recipientJid, mediaType)

        binding.tvStatus.text = "Initiating call to $recipientJid..."
    }

    private fun handleIncomingMessage(message: Message, elementType: String) {
        when (elementType) {
            "propose" -> {
                val proposeElement = message.getExtension<org.jivesoftware.smack.packet.StandardExtensionElement>(
                    "propose",
                    "urn:xmpp:jingle-message:0"
                )

                currentCallId = proposeElement?.getAttributeValue("id")
                currentPeerJid = message.from.toString()

                // Check if it's video or audio call
                val description = proposeElement?.getElements()?.find {
                    it.elementName == "description"
                }
                val mediaType = description?.getAttributeValue("media") ?: "audio"
                isVideoCall = mediaType == "video"

                binding.tvStatus.text = "Incoming ${mediaType} call from: ${message.from}"
                binding.llIncomingCall.visibility = android.view.View.VISIBLE
                binding.tvCallerInfo.text = "Caller: ${message.from}\nType: ${mediaType.uppercase()}"
            }
            "ringing" -> {
                binding.tvStatus.text = "Call ringing..."
            }
            "accept" -> {
                binding.tvStatus.text = "Call accepted by peer"
                binding.llIncomingCall.visibility = android.view.View.GONE
                showCallInProgress()
                startCallDuration()
            }
            "proceed" -> {
                binding.tvStatus.text = "Call proceeding..."
            }
        }
    }

    private fun answerCall() {
        currentCallId?.let { callId ->
            currentPeerJid?.let { peerJid ->
                // Initialize WebRTC for answering
                webRTCManager?.initializePeerConnection()
                webRTCManager?.acceptCall(isVideoCall)

                xmppManager?.acceptCall(peerJid, callId)
                binding.llIncomingCall.visibility = android.view.View.GONE
                showCallInProgress()
                binding.tvStatus.text = "Accepting call..."
            }
        }
    }

    private fun rejectCall() {
        currentCallId?.let { callId ->
            currentPeerJid?.let { peerJid ->
                xmppManager?.terminateCall(peerJid, callId)
            }
        }
        currentCallId = null
        currentPeerJid = null
        binding.llIncomingCall.visibility = android.view.View.GONE
        binding.cardMakeCall.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "Call rejected"
    }

    private fun endCall() {
        stopCallDuration()

        // End WebRTC call
        webRTCManager?.endCall()

        currentCallId?.let { callId ->
            currentPeerJid?.let { peerJid ->
                xmppManager?.terminateCall(peerJid, callId)
            }
        }

        currentCallId = null
        currentPeerJid = null
        binding.llCallControls.visibility = android.view.View.GONE
        binding.cardMakeCall.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "Call ended"
        binding.tvSdpInfo.text = ""
        binding.tvIceCandidates.text = ""

        // Reset call controls
        isMuted = false
        isSpeakerOn = false
        binding.btnMute.text = "🔇 Mute"
        binding.btnSpeaker.text = "🔊 Speaker"
    }

    private fun showCallInProgress() {
        binding.llCallControls.visibility = android.view.View.VISIBLE
        binding.llIncomingCall.visibility = android.view.View.GONE
        binding.cardMakeCall.visibility = android.view.View.GONE
        binding.tvPeerInfo.text = "Connected to: $currentPeerJid"
    }

    private fun startCallDuration() {
        callStartTime = System.currentTimeMillis()
        callDurationHandler = Handler(Looper.getMainLooper())
        callDurationRunnable = object : Runnable {
            override fun run() {
                val duration = (System.currentTimeMillis() - callStartTime) / 1000
                val minutes = duration / 60
                val seconds = duration % 60
                binding.tvCallDuration.text = String.format("%02d:%02d", minutes, seconds)
                callDurationHandler?.postDelayed(this, 1000)
            }
        }
        callDurationHandler?.post(callDurationRunnable!!)
    }

    private fun stopCallDuration() {
        callDurationRunnable?.let {
            callDurationHandler?.removeCallbacks(it)
        }
        callDurationHandler = null
        callDurationRunnable = null
        binding.tvCallDuration.text = "00:00"
    }

    private fun toggleMute() {
        isMuted = !isMuted
        webRTCManager?.toggleMute(isMuted)
        binding.btnMute.text = if (isMuted) "🔊 Unmute" else "🔇 Mute"
        Toast.makeText(
            this,
            if (isMuted) "Microphone muted" else "Microphone unmuted",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        binding.btnSpeaker.text = if (isSpeakerOn) "📱 Earpiece" else "🔊 Speaker"
        Toast.makeText(
            this,
            if (isSpeakerOn) "Speaker on" else "Speaker off",
            Toast.LENGTH_SHORT
        ).show()
        // TODO: Implement actual speaker toggle with audio manager
    }

    private fun sendJingleSessionInitiate(peerJid: String, sessionId: String, sdp: String) {
        // Delegate to XmppConnectionManager
        xmppManager?.sendSessionInitiate(peerJid, sessionId, sdp)
    }

    private fun sendJingleSessionAccept(peerJid: String, sessionId: String, sdp: String) {
        // Delegate to XmppConnectionManager
        xmppManager?.sendSessionAccept(peerJid, sessionId, sdp)
    }

    private fun sendIceCandidate(peerJid: String, sessionId: String, candidate: String,
                                 sdpMid: String, sdpMLineIndex: Int) {
        // Parse and send via XmppConnectionManager
        val jingleCandidate = parseWebRTCCandidate(candidate)
        xmppManager?.sendIceCandidate(peerJid, sessionId, jingleCandidate)
    }

    private fun parseWebRTCCandidate(candidateString: String): Candidate {
        // Parse WebRTC candidate string: "candidate:foundation component protocol priority ip port typ type"
        val parts = candidateString.split(" ")

        return Candidate().apply {
            if (parts.isNotEmpty() && parts[0].startsWith("candidate:")) {
                foundation = parts[0].substring(10) // Remove "candidate:" prefix
            }
            if (parts.size > 1) component = parts[1]
            if (parts.size > 2) protocol = parts[2]
            if (parts.size > 3) priority = parts[3].toIntOrNull() ?: 0
            if (parts.size > 4) ip = parts[4]
            if (parts.size > 5) port = parts[5].toIntOrNull() ?: 0
            if (parts.size > 7) type = parts[7] // parts[6] is "typ"
            id = "candidate_${System.currentTimeMillis()}"
            generation = 0
        }
    }

    private fun updateUIState(connected: Boolean) {
        binding.btnConnect.isEnabled = !connected
        binding.btnDisconnect.isEnabled = connected
        binding.etUsername.isEnabled = !connected
        binding.etPassword.isEnabled = !connected
        binding.etDomain.isEnabled = !connected
        binding.cardMakeCall.visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCallDuration()
        webRTCManager?.cleanup()
        xmppManager?.disconnect()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Permissions required for calls", Toast.LENGTH_LONG).show()
            }
        }
    }
}