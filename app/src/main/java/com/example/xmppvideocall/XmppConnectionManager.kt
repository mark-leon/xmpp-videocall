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

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onMessageReceived(message: Message, elementType: String)
        fun onCallInitiated(sessionId: String)
        fun onCallAccepted(sessionId: String)
    }

    private var listener: ConnectionListener? = null

    companion object {
        private const val TAG = "XmppConnectionManager"
    }

    fun setConnectionListener(listener: ConnectionListener) {
        this.listener = listener
    }

    fun connect() {
        scope.launch {
            try {
                val configBuilder = XMPPTCPConnectionConfiguration.builder()
                    .setUsernameAndPassword(username, password)
                    .setXmppDomain(domain)
                    .setHost("192.168.0.106")
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)

                host?.let { configBuilder.setHost(it) }
                configBuilder.setPort(port)

                val config = configBuilder.build()

                // Step 2: Create XMPP connection
                connection = XMPPTCPConnection(config)

                // Step 3: Connect to server
                connection?.connect()
                Log.d(TAG, "Connected to XMPP server")

                // Step 4: Login with credentials
                connection?.login()
                Log.d(TAG, "Logged in as ${connection?.user}")

                // Step 5: Notify UI on main thread
                withContext(Dispatchers.Main) {
                    listener?.onConnected()
                }

                // Step 6: Set presence to available
                val presence = Presence(Presence.Type.available).apply {
                    mode = Presence.Mode.available
                    status = "Ready for calls"
                }
                connection?.sendStanza(presence)

                // Step 7: Set up message listeners
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
            else -> Log.d(TAG, "Unknown element type: $elementType")
        }
    }

    private fun handleIncomingIQ(iq: IQ) {
        Log.d(TAG, "Received IQ: ${iq.toXML()}")

        try {
            JingleMessageHandler.handleJingleIqMessage(iq)
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
        val proposeElement = message.getExtension("propose", "urn:xmpp:jingle-message:0") as? StandardExtensionElement
        proposeElement?.let {
            val ringingId = it.getAttributeValue("id")
            Log.d(TAG, "Ringing with ID: $ringingId")

            val toJid = message.from.toString()
            val fromJid = message.to.toString()

            // Send ringing response
            sendMessage(MessageBuilder.buildRingingMessage(toJid, ringingId))

            // Send active message
            sendMessage(MessageBuilder.buildActiveMessage(toJid, ringingId))

            // Send proceed message
            sendMessage(MessageBuilder.buildProceedMessage(toJid, fromJid, ringingId))
        }
    }

    private fun handleAccept(message: Message) {
        val acceptElement = message.getExtension("accept", "urn:xmpp:jingle-message:0") as? StandardExtensionElement
        val acceptId = acceptElement?.getAttributeValue("id")

        acceptId?.let {
            Log.d(TAG, "Accept received with ID: $it")

            // Update session state
            jingleSessionManager.updateSessionState(it, JingleSessionManager.SessionState.ACTIVE)

            // Send Jingle session-initiate IQ
            scope.launch {
                try {
                    val myJid = connection?.user?.toString() ?: return@launch
                    val peerJid = message.from.toString()

                    val sessionInitiateIQ = jingleSessionManager.buildSessionInitiateIQ(
                        myJid,
                        peerJid,
                        it
                    )
                    connection?.sendStanza(sessionInitiateIQ)

                    Log.d(TAG, "Sent session-initiate IQ")

                } catch (e: Exception) {
                    Log.e(TAG, "Error sending session-initiate", e)
                }
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

    fun sendRingingResponse(toJid: String, callId: String) {
        sendMessage(MessageBuilder.buildRingingMessage(toJid, callId))
    }

    fun sendAcceptResponse(toJid: String, callId: String) {
        sendMessage(MessageBuilder.buildAcceptMessage(toJid, callId))
    }

    // Initiate outgoing call
    fun initiateCall(recipientJid: String, mediaType: String = "audio") {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                // Create Jingle session
                val session = jingleSessionManager.createSession(myJid, recipientJid, mediaType)

                // Send Jingle Message propose
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

    // Accept incoming call
    fun acceptCall(callerJid: String, sessionId: String) {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                // Update session state
                jingleSessionManager.updateSessionState(sessionId, JingleSessionManager.SessionState.PROCEEDING)

                // Send accept message
                sendMessage(MessageBuilder.buildAcceptMessage(callerJid, sessionId))

                // Send Jingle session-accept IQ
                val sessionAcceptIQ = jingleSessionManager.buildSessionAcceptIQ(
                    myJid,
                    callerJid,
                    sessionId
                )
                connection?.sendStanza(sessionAcceptIQ)

                Log.d(TAG, "Call accepted from $callerJid")

                withContext(Dispatchers.Main) {
                    listener?.onCallAccepted(sessionId)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error accepting call", e)
            }
        }
    }

    // Terminate call
    fun terminateCall(peerJid: String, sessionId: String) {
        scope.launch {
            try {
                val myJid = connection?.user?.toString() ?: return@launch

                // Send Jingle session-terminate IQ
                val terminateIQ = jingleSessionManager.buildSessionTerminateIQ(
                    myJid,
                    peerJid,
                    sessionId
                )
                connection?.sendStanza(terminateIQ)

                // Remove session
                jingleSessionManager.terminateSession(sessionId)

                Log.d(TAG, "Call terminated")

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