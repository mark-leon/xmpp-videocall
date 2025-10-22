package com.example.xmppvideocall

import android.util.Log
import com.example.xmppcall.handler.JingleMessageHandler
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

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onMessageReceived(message: Message, elementType: String)
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
                    .setHost("192.168.125.8")
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)

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

                // Set presence to available
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