package com.example.xmppvideocall

import android.app.Application

class XmppApplication : Application() {

    lateinit var webRtcManager: WebRtcManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        webRtcManager = WebRtcManager(this)
        webRtcManager.initialize()
    }

    companion object {
        lateinit var instance: XmppApplication
            private set
    }
}
