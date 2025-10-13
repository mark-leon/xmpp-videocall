package com.example.xmppvideocall

import org.jivesoftware.smack.XMPPConnection
import org.jxmpp.jid.Jid
import org.webrtc.*
import com.example.xmppvideocall.CallState

class JingleSession(
    private val connection: XMPPConnection,
    val sessionId: String,
    private val initiator: Jid,
    private val responder: Jid,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val eglBase: EglBase,
    val isInitiator: Boolean
) {
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var localMediaStream: MediaStream? = null

    var onRemoteStreamAdded: ((MediaStream) -> Unit)? = null
    var onCallStateChanged: ((CallState) -> Unit)? = null
    var onIceConnectionChange: ((PeerConnection.IceConnectionState) -> Unit)? = null

    private var currentState = CallState.IDLE
    private var isAudioEnabled = true
    private var isVideoEnabled = true

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            // Handle new ICE candidate (send via XMPP)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
            // Handle removed ICE candidates if necessary
        }

        override fun onAddStream(stream: MediaStream) {
            onRemoteStreamAdded?.invoke(stream)
        }

        override fun onRemoveStream(stream: MediaStream) {}

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {}

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            onIceConnectionChange?.invoke(newState)
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED -> updateState(CallState.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED -> updateState(CallState.ENDED)
                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}

        override fun onDataChannel(dataChannel: DataChannel) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
            // Optional: log or debug selected ICE candidate pair
        }
    }


    fun initiate(audioEnabled: Boolean, videoEnabled: Boolean) {
        updateState(CallState.INITIATING)
        createPeerConnection()
        setupLocalMedia(audioEnabled, videoEnabled)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", videoEnabled.toString()))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        updateState(CallState.RINGING)
                        // Send offer via XMPP Jingle
                    }
                    override fun onSetFailure(error: String) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun accept(audioEnabled: Boolean, videoEnabled: Boolean) {
        createPeerConnection()
        setupLocalMedia(audioEnabled, videoEnabled)
        updateState(CallState.CONNECTING)

        // Simulate accepting - in real implementation, process remote SDP
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // Send answer via XMPP
                        updateState(CallState.CONNECTED)
                    }
                    override fun onSetFailure(error: String) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, pcObserver)
    }

    private fun setupLocalMedia(audioEnabled: Boolean, videoEnabled: Boolean) {
        localMediaStream = peerConnectionFactory.createLocalMediaStream("local_stream")

        if (audioEnabled) {
            val audioConstraints = MediaConstraints()
            val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
            localMediaStream?.addTrack(localAudioTrack)
            isAudioEnabled = true
        }

        if (videoEnabled) {
            videoCapturer = createVideoCapturer()
            videoCapturer?.let { capturer ->
                val videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
                localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", videoSource)

                val surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    eglBase.eglBaseContext
                )

                capturer.initialize(surfaceTextureHelper, null, videoSource.capturerObserver)
                capturer.startCapture(1280, 720, 30)

                localMediaStream?.addTrack(localVideoTrack)
                isVideoEnabled = true
            }
        }

        localMediaStream?.let { peerConnection?.addStream(it) }
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(null)

        // Try front camera first
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }

        // Fall back to back camera
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }

        return null
    }

    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack

    fun toggleAudio(): Boolean {
        isAudioEnabled = !isAudioEnabled
        localAudioTrack?.setEnabled(isAudioEnabled)
        return isAudioEnabled
    }

    fun toggleVideo(): Boolean {
        isVideoEnabled = !isVideoEnabled
        localVideoTrack?.setEnabled(isVideoEnabled)
        return isVideoEnabled
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun terminate() {
        updateState(CallState.ENDED)
        close()
    }

    private fun updateState(newState: CallState) {
        currentState = newState
        onCallStateChanged?.invoke(newState)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        localAudioTrack?.dispose()
        localVideoTrack?.dispose()
        localMediaStream?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
    }
}
