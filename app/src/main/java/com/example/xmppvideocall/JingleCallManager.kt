package com.example.xmppvideocall

import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smackx.jingle.element.*
import org.jxmpp.jid.Jid
import java.util.*

class JingleCallManager(
    private val connection: XMPPConnection,
    private val webRtcManager: WebRtcManager
) {
    private val activeSessions = mutableMapOf<String, JingleSession>()

    var onIncomingCall: ((String, Jid, Boolean) -> Unit)? = null
    var onCallEnded: ((String) -> Unit)? = null

    init {
        setupJingleListener()
    }

    private fun setupJingleListener() {
        connection.addAsyncStanzaListener({ packet ->
            // Handle incoming Jingle IQs
            val stanza = packet.toString()
            if (stanza.contains("<jingle") && stanza.contains("session-initiate")) {
                handleIncomingCall(packet)
            }
        }, { true })
    }

    private fun handleIncomingCall(packet: org.jivesoftware.smack.packet.Stanza) {
        // Parse basic info from packet
        val from = packet.from
        val sessionId = UUID.randomUUID().toString()
        val hasVideo = packet.toString().contains("video")

        onIncomingCall?.invoke(sessionId, from, hasVideo)
    }

    fun initiateCall(recipientJid: Jid, audioEnabled: Boolean, videoEnabled: Boolean): JingleSession {
        val sessionId = UUID.randomUUID().toString()

        val session = JingleSession(
            connection = connection,
            sessionId = sessionId,
            initiator = connection.user,
            responder = recipientJid,
            peerConnectionFactory = webRtcManager.peerConnectionFactory,
            eglBase = webRtcManager.eglBase,
            isInitiator = true
        )

        activeSessions[sessionId] = session
        session.initiate(audioEnabled, videoEnabled)

        return session
    }

    fun acceptCall(sessionId: String, audioEnabled: Boolean, videoEnabled: Boolean): JingleSession? {
        return activeSessions[sessionId]?.apply {
            accept(audioEnabled, videoEnabled)
        }
    }

    fun getSession(sessionId: String): JingleSession? = activeSessions[sessionId]

    fun terminateSession(sessionId: String) {
        activeSessions[sessionId]?.terminate()
        activeSessions.remove(sessionId)
        onCallEnded?.invoke(sessionId)
    }

    fun cleanup() {
        activeSessions.values.forEach { it.close() }
        activeSessions.clear()
    }
}
