package com.example.xmppvideocall

import android.content.Context
import org.webrtc.*


class WebRtcManager(private val context: Context) {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null

    // Callbacks
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    // Legacy: kept for backwards compatibility but will NOT be called in Unified Plan.
    var onAddStream: ((MediaStream) -> Unit)? = null
    // Use this for Unified Plan remote tracks:
    var onTrack: ((RtpReceiver, Array<out MediaStream>) -> Unit)? = null
    var onIceConnectionChange: ((PeerConnection.IceConnectionState) -> Unit)? = null

    private val eglBase = EglBase.create()

    fun initialize() {
        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(stunServer: String = "stun:stun.l.google.com:19302"): Boolean {
        val iceServers = listOf(
            PeerConnection.IceServer.builder(stunServer).createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // make semantics explicit
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke(candidate)
            }

            // Plan B callback (deprecated). Will likely not be called in Unified Plan.
            override fun onAddStream(stream: MediaStream) {
                onAddStream?.invoke(stream)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onIceConnectionChange?.invoke(state)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            // Unified Plan: handle incoming tracks here
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                // Some Android/webrtc versions call onAddTrack instead of onTrack; handle both
                if (receiver != null && mediaStreams != null) {
                    onTrack?.invoke(receiver, mediaStreams)
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                // Newer callback: transceiver provides receiver and track info
                transceiver?.receiver?.let { receiver ->
                    // mediaStreams may be obtained from transceiver.receiver.track() metadata or left empty
                    // Here we pass an empty array; the receiver object itself has the track.
                    onTrack?.invoke(receiver, arrayOf())
                }
            }
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })

        return peerConnection != null
    }

    fun startLocalMedia(hasVideo: Boolean, localRenderer: SurfaceViewRenderer? = null) {
        // Audio
        val audioConstraints = MediaConstraints()
        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)

        // Video
        if (hasVideo && localRenderer != null) {
            localRenderer.init(eglBase.eglBaseContext, null)
            localRenderer.setMirror(true)

            videoCapturer = createCameraCapturer()
            videoSource = peerConnectionFactory?.createVideoSource(videoCapturer?.isScreencast ?: false)
            localVideoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)

            videoCapturer?.initialize(
                SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
                context,
                videoSource?.capturerObserver
            )

            videoCapturer?.startCapture(1280, 720, 30)
            localVideoTrack?.addSink(localRenderer)
        }

        // ===== Unified Plan: add tracks (or transceivers) instead of addStream =====
        localAudioTrack?.let { audioTrack ->
            // Adding with streamId is optional. If you want a streamId for legacy interop:
            val audioSender = peerConnection?.addTrack(audioTrack, listOf("local_stream"))
            // Optionally configure sender parameters here...
        }

        localVideoTrack?.let { videoTrack ->
            // Use addTransceiver for better control (direction, codecs, etc.)
            try {
                // If you want to use addTrack:
                val videoSender = peerConnection?.addTrack(videoTrack, listOf("local_stream"))
                // And/or create a transceiver:
                peerConnection?.addTransceiver(
                    videoTrack,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                )
            } catch (e: Exception) {
                // Fallback: try addTransceiver only
                peerConnection?.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                )
            }
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Try front camera first
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }

        // Fallback to back camera
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }

        return null
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        callback(desc)
                    }
                    override fun onSetFailure(error: String) {}
                    override fun onCreateSuccess(p0: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, desc)
            }

            override fun onCreateFailure(error: String) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        callback(desc)
                    }
                    override fun onSetFailure(error: String) {}
                    override fun onCreateSuccess(p0: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, desc)
            }

            override fun onCreateFailure(error: String) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: String, type: SessionDescription.Type) {
        val remoteDesc = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
            override fun onCreateSuccess(p0: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, remoteDesc)
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun toggleMute(): Boolean {
        localAudioTrack?.let {
            it.setEnabled(!it.enabled())
            return it.enabled()
        }
        return false
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun release() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) { /* ignore */ }
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()
        eglBase.release()
    }
}

