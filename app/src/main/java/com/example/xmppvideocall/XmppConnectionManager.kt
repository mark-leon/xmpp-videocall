package com.example.xmppvideocall

import kotlinx.coroutines.*
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.provider.ProviderManager
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.EntityFullJid
import java.util.*

class XmppConnectionManager {

    private var connection: AbstractXMPPConnection? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onIncomingCall: ((String, String, Boolean) -> Unit)? = null
    var onCallAccepted: ((String, String) -> Unit)? = null
    var onIceCandidate: ((String, String, String, Int, String) -> Unit)? = null
    var onCallTerminated: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    fun connect(server: String, username: String, password: String) {
        scope.launch {
            try {
                val config = XMPPTCPConnectionConfiguration.builder()
                    .setXmppDomain(server)
                    .setHost("192.168.125.8")
                    .setPort(5222)
                    .setUsernameAndPassword(username, password)
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)
                    .setCompressionEnabled(false)
                    .build()

                connection = XMPPTCPConnection(config)
                connection?.connect()
                connection?.login()

                setupJingleListener()

                withContext(Dispatchers.Main) {
                    onConnectionChanged?.invoke(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onConnectionChanged?.invoke(false)
                }
            }
        }
    }

    private fun setupJingleListener() {
        connection?.addAsyncStanzaListener({ stanza ->
            if (stanza is IQ && stanza.type == IQ.Type.set) {
                handleJingleIQ(stanza)
            }
        }, { stanza ->
            stanza is IQ && stanza.hasExtension("jingle", "urn:xmpp:jingle:1")
        })
    }

    private fun handleJingleIQ(iq: IQ) {
        val jingle = iq.getExtension<StandardExtensionElement>("jingle", "urn:xmpp:jingle:1")
        val action = jingle?.getAttributeValue("action") ?: return
        val sid = jingle.getAttributeValue("sid") ?: return

        when (action) {
            "session-initiate" -> {
                val sdp = extractSdp(jingle)
                val hasVideo = sdp.contains("m=video")
                onIncomingCall?.invoke(iq.from.toString(), sid, hasVideo)
                sendAck(iq)
            }
            "session-accept" -> {
                val sdp = extractSdp(jingle)
                onCallAccepted?.invoke(sid, sdp)
                sendAck(iq)
            }
            "transport-info" -> {
                val candidate = extractIceCandidate(jingle)
                candidate?.let { (sdpMid, sdpMLineIndex, candidateSdp) ->
                    onIceCandidate?.invoke(sid, sdpMid, candidateSdp, sdpMLineIndex, "")
                }
                sendAck(iq)
            }
            "session-terminate" -> {
                onCallTerminated?.invoke(sid)
                sendAck(iq)
            }
        }
    }

    private fun extractSdp(jingle: StandardExtensionElement): String {
        // Look for custom SDP element or build from Jingle description
        val sdpElement = jingle.elements.find { it.elementName == "sdp" }
        return sdpElement?.text ?: buildSdpFromJingle(jingle)
    }

    private fun buildSdpFromJingle(jingle: StandardExtensionElement): String {
        // Simplified: In real implementation, parse Jingle RTP description
        // and construct proper SDP
        return "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
    }

    private fun extractIceCandidate(jingle: StandardExtensionElement): Triple<String, Int, String>? {
        val content = jingle.elements.find { it.elementName == "content" } ?: return null
        val transport = content.elements.find { it.elementName == "transport" } ?: return null
        val candidate = transport.elements.find { it.elementName == "candidate" } ?: return null

        val foundation = candidate.getAttributeValue("foundation") ?: ""
        val component = candidate.getAttributeValue("component") ?: "1"
        val protocol = candidate.getAttributeValue("protocol") ?: "udp"
        val priority = candidate.getAttributeValue("priority") ?: "0"
        val ip = candidate.getAttributeValue("ip") ?: ""
        val port = candidate.getAttributeValue("port") ?: "0"
        val type = candidate.getAttributeValue("type") ?: "host"

        val sdpMid = content.getAttributeValue("name") ?: "audio"
        val sdpMLineIndex = 0
        val candidateSdp = "candidate:$foundation $component $protocol $priority $ip $port typ $type"

        return Triple(sdpMid, sdpMLineIndex, candidateSdp)
    }

    private fun sendAck(iq: IQ) {
        val ack = IQ.createResultIQ(iq)
        connection?.sendStanza(ack)
    }

    fun sendSessionInitiate(to: String, sid: String, sdp: String, hasVideo: Boolean) {
        scope.launch {
            try {
                val iq = createSessionInitiateIQ(to, sid, sdp, hasVideo)
                connection?.sendStanza(iq)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendSessionAccept(to: String, sid: String, sdp: String) {
        scope.launch {
            try {
                val iq = createSessionAcceptIQ(to, sid, sdp)
                connection?.sendStanza(iq)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendIceCandidate(to: String, sid: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        scope.launch {
            try {
                val iq = createTransportInfoIQ(to, sid, candidate, sdpMid, sdpMLineIndex)
                connection?.sendStanza(iq)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendSessionTerminate(to: String, sid: String) {
        scope.launch {
            try {
                val iq = createSessionTerminateIQ(to, sid)
                connection?.sendStanza(iq)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createSessionInitiateIQ(to: String, sid: String, sdp: String, hasVideo: Boolean): IQ {
        val iq = object : IQ("jingle", "urn:xmpp:jingle:1") {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-initiate")
                xml.attribute("sid", sid)
                xml.rightAngleBracket()
                // Add SDP as custom element
                xml.append("<sdp xmlns='urn:xmpp:jingle:apps:rtp:sdp:1'>")
                xml.escape(sdp)
                xml.append("</sdp>")
                return xml
            }
        }
        iq.type = IQ.Type.set  // THIS IS CRITICAL
        iq.to = JidCreate.from(to)
        iq.stanzaId = UUID.randomUUID().toString()
        return iq
    }

    private fun createSessionAcceptIQ(to: String, sid: String, sdp: String): IQ {
        val iq = object : IQ("jingle", "urn:xmpp:jingle:1") {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-accept")
                xml.attribute("sid", sid)
                xml.rightAngleBracket()
                xml.append("<sdp xmlns='urn:xmpp:jingle:apps:rtp:sdp:1'>")
                xml.escape(sdp)
                xml.append("</sdp>")
                return xml
            }
        }
        iq.type = IQ.Type.set  // THIS IS CRITICAL
        iq.to = JidCreate.from(to)
        iq.stanzaId = UUID.randomUUID().toString()
        return iq
    }

    private fun createTransportInfoIQ(to: String, sid: String, candidate: String, sdpMid: String, sdpMLineIndex: Int): IQ {
        val iq = object : IQ("jingle", "urn:xmpp:jingle:1") {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "transport-info")
                xml.attribute("sid", sid)
                xml.rightAngleBracket()
                xml.append("<candidate>")
                xml.escape(candidate)
                xml.append("</candidate>")
                return xml
            }
        }
        iq.type = IQ.Type.set  // THIS IS CRITICAL
        iq.to = JidCreate.from(to)
        iq.stanzaId = UUID.randomUUID().toString()
        return iq
    }

    private fun createSessionTerminateIQ(to: String, sid: String): IQ {
        val iq = object : IQ("jingle", "urn:xmpp:jingle:1") {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-terminate")
                xml.attribute("sid", sid)
                xml.rightAngleBracket()
                return xml
            }
        }
        iq.type = IQ.Type.set  // THIS IS CRITICAL
        iq.to = JidCreate.from(to)
        iq.stanzaId = UUID.randomUUID().toString()
        return iq
    }

    fun disconnect() {
        connection?.disconnect()
        connection = null
        scope.cancel()
    }

    fun getOwnJid(): String? {
        return connection?.user?.toString()
    }
}
