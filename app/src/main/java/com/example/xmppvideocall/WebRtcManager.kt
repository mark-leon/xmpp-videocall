package com.example.xmppvideocall

import android.content.Context
import org.webrtc.*

class WebRtcManager(private val context: Context) {
    lateinit var peerConnectionFactory: PeerConnectionFactory
    lateinit var eglBase: EglBase

    fun initialize() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        eglBase = EglBase.create()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true
        )

        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun cleanup() {
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}
