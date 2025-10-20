package com.example.xmppvideocall

import android.app.Application
import org.webrtc.PeerConnectionFactory

class CallApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
    }
}
