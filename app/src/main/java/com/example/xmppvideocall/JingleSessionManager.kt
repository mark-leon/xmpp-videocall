package com.example.xmppvideocall

import android.util.Log
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.util.StringUtils
import java.util.*

class JingleSessionManager {

    companion object {
        private const val TAG = "JingleSessionManager"
    }

    data class JingleSession(
        val sessionId: String,
        val initiator: String,
        val responder: String,
        val mediaType: String,
        var state: SessionState = SessionState.PENDING
    )

    enum class SessionState {
        PENDING,
        RINGING,
        PROCEEDING,
        ACTIVE,
        TERMINATED
    }

    private val activeSessions = mutableMapOf<String, JingleSession>()

    fun createSession(initiator: String, responder: String, mediaType: String): JingleSession {
        val sessionId = generateSessionId()
        val session = JingleSession(sessionId, initiator, responder, mediaType)
        activeSessions[sessionId] = session
        Log.d(TAG, "Created session: $sessionId")
        return session
    }

    fun getSession(sessionId: String): JingleSession? {
        return activeSessions[sessionId]
    }

    fun updateSessionState(sessionId: String, state: SessionState) {
        activeSessions[sessionId]?.state = state
        Log.d(TAG, "Updated session $sessionId state to $state")
    }

    fun terminateSession(sessionId: String) {
        activeSessions.remove(sessionId)
        Log.d(TAG, "Terminated session: $sessionId")
    }

    private fun generateSessionId(): String {
        return StringUtils.randomString(22)
    }

    // Build Jingle session-initiate IQ
    fun buildSessionInitiateIQ(
        from: String,
        to: String,
        sessionId: String,
        mediaType: String = "audio"
    ): IQ {
        val iq = object : IQ("jingle", "urn:xmpp:jingle:1") {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-initiate")
                xml.attribute("sid", sessionId)
                xml.attribute("initiator", from)
                xml.rightAngleBracket()

                // Content element
                xml.openElement("content")
                xml.attribute("creator", "initiator")
                xml.attribute("name", "0")
                xml.attribute("senders", "both")

                // Description (RTP)
                xml.openElement("description")
                xml.xmlnsAttribute("urn:xmpp:jingle:apps:rtp:1")
                xml.attribute("media", mediaType)

                // Payload types for audio
                if (mediaType == "audio") {
                    addAudioPayloadTypes(xml)
                }

                xml.closeElement("description")

                // Transport (ICE-UDP)
                xml.openElement("transport")
                xml.xmlnsAttribute("urn:xmpp:jingle:transports:ice-udp:1")
                xml.attribute("ufrag", generateRandomString(8))
                xml.attribute("pwd", generateRandomString(24))

                // DTLS fingerprint
                xml.openElement("fingerprint")
                xml.xmlnsAttribute("urn:xmpp:jingle:apps:dtls:0")
                xml.attribute("hash", "sha-256")
                xml.attribute("setup", "actpass")
                xml.append(generateDummyFingerprint())
                xml.closeElement("fingerprint")

                xml.closeElement("transport")
                xml.closeElement("content")

                // Group (BUNDLE)
                xml.openElement("group")
                xml.xmlnsAttribute("urn:xmpp:jingle:apps:grouping:0")
                xml.attribute("semantics", "BUNDLE")
                xml.openElement("content")
                xml.attribute("name", "0")
                xml.closeElement("content")
                xml.closeElement("group")

                return xml
            }
        }

        iq.type = IQ.Type.set
        iq.to = org.jxmpp.jid.impl.JidCreate.from(to)
        iq.from = org.jxmpp.jid.impl.JidCreate.from(from)

        return iq
    }

    // Build Jingle session-accept IQ
    fun buildSessionAcceptIQ(
        from: String,
        to: String,
        sessionId: String,
        mediaType: String = "audio"
    ): IQ {
        val iq = object : IQ("jingle", "urn:xmpp:jingle:1") {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-accept")
                xml.attribute("sid", sessionId)
                xml.attribute("responder", from)
                xml.rightAngleBracket()

                // Content element
                xml.openElement("content")
                xml.attribute("creator", "initiator")
                xml.attribute("name", "0")
                xml.attribute("senders", "both")

                // Description (RTP)
                xml.openElement("description")
                xml.xmlnsAttribute("urn:xmpp:jingle:apps:rtp:1")
                xml.attribute("media", mediaType)

                if (mediaType == "audio") {
                    addAudioPayloadTypes(xml)
                }

                xml.closeElement("description")

                // Transport (ICE-UDP)
                xml.openElement("transport")
                xml.xmlnsAttribute("urn:xmpp:jingle:transports:ice-udp:1")
                xml.attribute("ufrag", generateRandomString(8))
                xml.attribute("pwd", generateRandomString(24))

                // DTLS fingerprint
                xml.openElement("fingerprint")
                xml.xmlnsAttribute("urn:xmpp:jingle:apps:dtls:0")
                xml.attribute("hash", "sha-256")
                xml.attribute("setup", "active")
                xml.append(generateDummyFingerprint())
                xml.closeElement("fingerprint")

                xml.closeElement("transport")
                xml.closeElement("content")

                return xml
            }
        }

        iq.type = IQ.Type.set
        iq.to = org.jxmpp.jid.impl.JidCreate.from(to)
        iq.from = org.jxmpp.jid.impl.JidCreate.from(from)

        return iq
    }

    // Build Jingle session-terminate IQ
    fun buildSessionTerminateIQ(
        from: String,
        to: String,
        sessionId: String,
        reason: String = "success"
    ): IQ {
        val iq = object : IQ("jingle", "urn:xmpp:jingle:1") {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-terminate")
                xml.attribute("sid", sessionId)
                xml.rightAngleBracket()

                xml.openElement("reason")
                xml.emptyElement(reason)
                xml.closeElement("reason")

                return xml
            }
        }

        iq.type = IQ.Type.set
        iq.to = org.jxmpp.jid.impl.JidCreate.from(to)
        iq.from = org.jxmpp.jid.impl.JidCreate.from(from)

        return iq
    }

    private fun addAudioPayloadTypes(xml: IQ.IQChildElementXmlStringBuilder) {
        // Opus codec
        xml.openElement("payload-type")
        xml.attribute("id", "111")
        xml.attribute("name", "opus")
        xml.attribute("clockrate", "48000")
        xml.attribute("channels", "2")

        xml.openElement("parameter")
        xml.attribute("name", "minptime")
        xml.attribute("value", "10")
        xml.closeEmptyElement()

        xml.openElement("parameter")
        xml.attribute("name", "useinbandfec")
        xml.attribute("value", "1")
        xml.closeEmptyElement()

        xml.closeElement("payload-type")

        // PCMU codec
        xml.openElement("payload-type")
        xml.attribute("id", "0")
        xml.attribute("name", "PCMU")
        xml.attribute("clockrate", "8000")
        xml.closeEmptyElement()

        // PCMA codec
        xml.openElement("payload-type")
        xml.attribute("id", "8")
        xml.attribute("name", "PCMA")
        xml.attribute("clockrate", "8000")
        xml.closeEmptyElement()
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    private fun generateDummyFingerprint(): String {
        return (1..32)
            .map { "0123456789ABCDEF".random() }
            .joinToString("")
            .chunked(2)
            .joinToString(":")
    }
}