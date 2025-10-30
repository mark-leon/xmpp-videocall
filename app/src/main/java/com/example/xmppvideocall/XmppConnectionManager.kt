package com.example.xmppvideocall

import android.util.Log
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import kotlinx.coroutines.*
import org.jivesoftware.smack.ConnectionConfiguration

class XmppConnectionManager(
    private val username: String,
    private val password: String,
    private val domain: String,
    private val host: String? = null,
    private val port: Int = 5222
) {
    private var connection: AbstractXMPPConnection? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jingleSessionManager = JingleSessionManager()
    private var webRTCManager: WebRTCCallManager? = null

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onMessageReceived(message: Message, elementType: String)
        fun onCallInitiated(sessionId: String)
        fun onCallAccepted(sessionId: String)
        fun onSdpReceived(sdp: String, type: String, sessionId: String)
        fun onIceCandidateReceived(candidate: Candidate, sessionId: String)
    }

    private var listener: ConnectionListener? = null

    companion object {
        private const val TAG = "XmppConnectionManager"
    }

    fun setConnectionListener(listener: ConnectionListener) {
        this.listener = listener
    }

    fun setWebRTCManager(manager: WebRTCCallManager) {
        this.webRTCManager = manager
    }

    fun connect() {
        scope.launch {
            try {
                val configBuilder = XMPPTCPConnectionConfiguration.builder()
                    .setUsernameAndPassword(username, password)
                    .setXmppDomain(domain)
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.required)

                host?.let { configBuilder.setHost(it) }
                configBuilder.setPort(port)

                val config = configBuilder.build()
                connection = XMPPTCPConnection(config)
                connection?.connect()
                Log.d(TAG, "Connected to XMPP server")

                connection?.login()
                Log.d(TAG, "Logged in as ${connection?.user}")

                withContext(Dispatchers.Main) {
                    listener?.onConnected()
                }

                val presence = Presence(Presence.Type.available).apply {
                    mode = Presence.Mode.available
                    status = "Ready for calls"
                }
                connection?.sendStanza(presence)

                setupListeners()

            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                withContext(Dispatchers.Main) {
                    listener?.onError(e.message ?: "Connection failed")
                }
            }
        }
    }

    private fun setupListeners() {
        // Message listener
        connection?.addAsyncStanzaListener({ stanza ->
            if (stanza is Message) {
                handleIncomingMessage(stanza)
            }
        }, { it is Message })

        // IQ listener
        connection?.addAsyncStanzaListener({ stanza ->
            if (stanza is IQ) {
                handleIncomingIQ(stanza)
            }
        }, { it is IQ })
    }

    private fun handleIncomingMessage(message: Message) {
        Log.d(TAG, "Received message: ${message.toXML()}")

        val elementType = determineElementType(message)

        scope.launch(Dispatchers.Main) {
            listener?.onMessageReceived(message, elementType)
        }

        when (elementType) {
            "propose" -> handlePropose(message)
            "ringing" -> handleRinging(message)
            "accept" -> handleAccept(message)
            "proceed" -> handleProceed(message)
            else -> Log.d(TAG, "Unknown element type: $elementType")
        }
    }

    private fun handleIncomingIQ(iq: IQ) {
        Log.d(TAG, "Received IQ: ${iq.toXML()}")

        try {
            val action = JingleMessageHandler.handleJingleIqMessage(iq)

            // Get session ID from IQ
            val jingleElement = iq.toString()
            val sidMatch = Regex("""sid="([^"]+)"""").find(jingleElement)
            val sessionId = sidMatch?.groupValues?.get(1) ?: ""

            when (action) {
                "session-initiate", "session-accept" -> {
                    // SDP is already handled by JingleMessageHandler callback
                    Log.d(TAG, "Session $action for session: $sessionId")
                }
                "transport-info" -> {
                    // ICE candidates are already handled by JingleMessageHandler callback
                    Log.d(TAG, "Transport info for session: $sessionId")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling Jingle IQ", e)
        }
    }

    private fun handlePropose(message: Message) {
        val proposeElement = message.getExtension("propose", "urn:xmpp:jingle-message:0") as? StandardExtensionElement
        val proposeId = proposeElement?.getAttributeValue("id")

        proposeId?.let {
            Log.d(TAG, "Propose received with ID: $it")
        }
    }

    private fun handleRinging(message: Message) {
        Log.d(TAG, "Ringing message received")
    }

    private fun handleProceed(message: Message) {
        val proceedElement = message.getExtension("proceed", "urn:xmpp:jingle-message:0") as? StandardExtensionElement
        proceedElement?.let {
            val sessionId = it.getAttributeValue("id")
            Log.d(TAG, "Proceed with ID: $sessionId")
        }
    }

    private fun handleAccept(message: Message) {
        val acceptElement = message.getExtension("accept", "urn:xmpp:jingle-message:0") as? StandardExtensionElement
        val acceptId = acceptElement?.getAttributeValue("id")

        acceptId?.let {
            Log.d(TAG, "Accept received with ID: $it")
            jingleSessionManager.updateSessionState(it, JingleSessionManager.SessionState.ACTIVE)

            // Notify listener
            scope.launch(Dispatchers.Main) {
                listener?.onCallAccepted(it)
            }
        }
    }

    fun sendMessage(message: Message) {
        scope.launch {
            try {
                connection?.sendStanza(message)
                Log.d(TAG, "Message sent: ${message.toXML()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
            }
        }
    }

    fun sendJingleIQ(iq: IQ) {
        scope.launch {
            try {
                connection?.sendStanza(iq)
                Log.d(TAG, "Jingle IQ sent: ${iq.toXML()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending Jingle IQ", e)
            }
        }
    }

    fun sendRingingResponse(toJid: String, callId: String) {
        sendMessage(MessageBuilder.buildRingingMessage(toJid, callId))
    }

    fun sendAcceptResponse(toJid: String, callId: String) {
        sendMessage(MessageBuilder.buildAcceptMessage(toJid, callId))
    }

    fun initiateCall(recipientJid: String, mediaType: String = "audio") {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                val session = jingleSessionManager.createSession(myJid, recipientJid, mediaType)
                val proposeMessage = MessageBuilder.buildProposeMessage(recipientJid, session.sessionId)
                connection?.sendStanza(proposeMessage)

                Log.d(TAG, "Call initiated to $recipientJid with session ${session.sessionId}")

                withContext(Dispatchers.Main) {
                    listener?.onCallInitiated(session.sessionId)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error initiating call", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("Failed to initiate call: ${e.message}")
                }
            }
        }
    }

    fun acceptCall(callerJid: String, sessionId: String) {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                jingleSessionManager.updateSessionState(sessionId, JingleSessionManager.SessionState.PROCEEDING)
                sendMessage(MessageBuilder.buildAcceptMessage(callerJid, sessionId))

                Log.d(TAG, "Call accepted from $callerJid")

                withContext(Dispatchers.Main) {
                    listener?.onCallAccepted(sessionId)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error accepting call", e)
            }
        }
    }

    fun sendSessionInitiate(peerJid: String, sessionId: String, sdp: String) {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                // Convert SDP to Jingle format
                val jingleMessage = SDPJingleConverter.sdpToJingle(sdp, sessionId)
                jingleMessage.action = "session-initiate"

                // Build and send Jingle IQ
                val sessionInitiateIQ = jingleSessionManager.buildSessionInitiateIQ(
                    myJid,
                    peerJid,
                    sessionId
                )
                connection?.sendStanza(sessionInitiateIQ)

                Log.d(TAG, "Sent session-initiate IQ for session: $sessionId")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending session-initiate", e)
            }
        }
    }

    fun sendSessionAccept(peerJid: String, sessionId: String, sdp: String) {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                // Convert SDP to Jingle format
                val jingleMessage = SDPJingleConverter.sdpToJingle(sdp, sessionId)
                jingleMessage.action = "session-accept"

                // Build and send Jingle IQ
                val sessionAcceptIQ = jingleSessionManager.buildSessionAcceptIQ(
                    myJid,
                    peerJid,
                    sessionId
                )
                connection?.sendStanza(sessionAcceptIQ)

                Log.d(TAG, "Sent session-accept IQ for session: $sessionId")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending session-accept", e)
            }
        }
    }

    fun sendIceCandidate(peerJid: String, sessionId: String, candidate: Candidate) {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                // Build transport-info IQ with ICE candidate
                // This would need to be implemented in JingleSessionManager
                Log.d(TAG, "Sending ICE candidate for session: $sessionId")

                // For now, log the candidate
                Log.d(TAG, "ICE Candidate: ${candidate.ip}:${candidate.port} type=${candidate.type}")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending ICE candidate", e)
            }
        }
    }

    fun terminateCall(peerJid: String, sessionId: String) {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                val terminateIQ = jingleSessionManager.buildSessionTerminateIQ(
                    myJid,
                    peerJid,
                    sessionId
                )
                connection?.sendStanza(terminateIQ)
                jingleSessionManager.terminateSession(sessionId)

                Log.d(TAG, "Call terminated for session: $sessionId")

            } catch (e: Exception) {
                Log.e(TAG, "Error terminating call", e)
            }
        }
    }

    fun getJingleSessionManager(): JingleSessionManager {
        return jingleSessionManager
    }

    private fun determineElementType(message: Message): String {
        return when {
            message.getExtension<StandardExtensionElement>("propose", "urn:xmpp:jingle-message:0") != null -> "propose"
            message.getExtension<StandardExtensionElement>("ringing", "urn:xmpp:jingle-message:0") != null -> "ringing"
            message.getExtension<StandardExtensionElement>("accept", "urn:xmpp:jingle-message:0") != null -> "accept"
            message.getExtension<StandardExtensionElement>("proceed", "urn:xmpp:jingle-message:0") != null -> "proceed"
            message.getExtension<StandardExtensionElement>("reject", "urn:xmpp:jingle-message:0") != null -> "reject"
            else -> "unknown"
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                if (connection?.isConnected == true) {
                    connection?.disconnect()
                    Log.d(TAG, "Disconnected from XMPP server")
                    withContext(Dispatchers.Main) {
                        listener?.onDisconnected()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting", e)
            }
        }
    }

    fun isConnected(): Boolean = connection?.isConnected == true
}