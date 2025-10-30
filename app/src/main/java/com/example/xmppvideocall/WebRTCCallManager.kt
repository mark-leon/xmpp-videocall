package com.example.xmppvideocall

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRTCCallManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRTCCallManager"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var isInitiator = false

    private var rtcListener: RTCListener? = null

    interface RTCListener {
        fun onLocalSdpCreated(sdp: String, type: String)
        fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onIceConnectionChange(state: PeerConnection.IceConnectionState)
        fun onCallConnected()
        fun onCallDisconnected()
        fun onRemoteVideoTrack(track: VideoTrack)
    }

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("")
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    fun initializePeerConnection(
        iceServers: List<PeerConnection.IceServer> = getDefaultIceServers()
    ) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "New ICE candidate: ${it.sdp}")
                        rtcListener?.onIceCandidate(it.sdp, it.sdpMid, it.sdpMLineIndex)
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    Log.d(TAG, "ICE candidates removed")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state: $state")
                    state?.let {
                        rtcListener?.onIceConnectionChange(it)
                        when (it) {
                            PeerConnection.IceConnectionState.CONNECTED -> rtcListener?.onCallConnected()
                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.CLOSED -> rtcListener?.onCallDisconnected()
                            else -> {}
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state: $state")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering state: $state")
                }

                override fun onAddStream(stream: MediaStream?) {
                    Log.d(TAG, "Stream added: ${stream?.id}")
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    Log.d(TAG, "Stream removed: ${stream?.id}")
                }

                override fun onDataChannel(channel: DataChannel?) {
                    Log.d(TAG, "Data channel: ${channel?.label()}")
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.d(TAG, "Track added: ${receiver?.track()?.kind()}")
                    receiver?.track()?.let { track ->
                        if (track.kind() == "video" && track is VideoTrack) {
                            rtcListener?.onRemoteVideoTrack(track)
                        }
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE connection receiving: $receiving")
                }
            }
        )

        Log.d(TAG, "PeerConnection initialized")
    }

    fun startCall(isVideoCall: Boolean = false) {
        isInitiator = true
        createLocalAudioTrack()
        if (isVideoCall) {
            createLocalVideoTrack()
        }
        createOffer()
    }

    fun acceptCall(isVideoCall: Boolean = false) {
        isInitiator = false
        createLocalAudioTrack()
        if (isVideoCall) {
            createLocalVideoTrack()
        }
    }

    private fun createLocalAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack(
            "audio_track_${System.currentTimeMillis()}",
            audioSource
        )
        localAudioTrack?.setEnabled(true)

        peerConnection?.addTrack(
            localAudioTrack,
            listOf("stream_${System.currentTimeMillis()}")
        )

        Log.d(TAG, "Local audio track created and added")
    }

    private fun createLocalVideoTrack() {
        val videoCapturer = createCameraCapturer() ?: return
        this.videoCapturer = videoCapturer

        val surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            EglBase.create().eglBaseContext
        )

        videoSource = peerConnectionFactory?.createVideoSource(videoCapturer.isScreencast)
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory?.createVideoTrack(
            "video_track_${System.currentTimeMillis()}",
            videoSource
        )
        localVideoTrack?.setEnabled(true)

        peerConnection?.addTrack(
            localVideoTrack,
            listOf("stream_${System.currentTimeMillis()}")
        )

        Log.d(TAG, "Local video track created and added")
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Try front camera first
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }

        // Try back camera
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }

        return null
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                if (localVideoTrack != null) "true" else "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.d(TAG, "Offer created successfully")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}

                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set successfully")
                            rtcListener?.onLocalSdpCreated(it.description, it.type.canonicalForm())
                        }

                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }

                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }
                    }, it)
                }
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set failure: $error")
            }
        }, constraints)
    }

    fun handleRemoteOffer(sdp: String) {
        val remoteDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}

            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
                createAnswer()
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create failure: $error")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set remote description failed: $error")
            }
        }, remoteDescription)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                if (localVideoTrack != null) "true" else "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.d(TAG, "Answer created successfully")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}

                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description (answer) set successfully")
                            rtcListener?.onLocalSdpCreated(it.description, it.type.canonicalForm())
                        }

                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Create failure: $error")
                        }

                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description (answer) failed: $error")
                        }
                    }, it)
                }
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set failure: $error")
            }
        }, constraints)
    }

    fun handleRemoteAnswer(sdp: String) {
        val remoteDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}

            override fun onSetSuccess() {
                Log.d(TAG, "Remote answer set successfully")
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create failure: $error")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set remote answer failed: $error")
            }
        }, remoteDescription)
    }

    fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
        Log.d(TAG, "ICE candidate added")
    }

    fun toggleMute(mute: Boolean) {
        localAudioTrack?.setEnabled(!mute)
        Log.d(TAG, "Audio ${if (mute) "muted" else "unmuted"}")
    }

    fun toggleVideo(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        Log.d(TAG, "Video ${if (enabled) "enabled" else "disabled"}")
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
        Log.d(TAG, "Camera switched")
    }

    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack

    fun endCall() {
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null

        localVideoTrack?.setEnabled(false)
        localVideoTrack?.dispose()
        localVideoTrack = null

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        audioSource?.dispose()
        audioSource = null

        videoSource?.dispose()
        videoSource = null

        peerConnection?.close()
        peerConnection = null

        Log.d(TAG, "Call ended and resources released")
    }

    fun setRTCListener(listener: RTCListener) {
        this.rtcListener = listener
    }

    private fun getDefaultIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
    }

    fun cleanup() {
        endCall()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        Log.d(TAG, "WebRTC manager cleaned up")
    }
}