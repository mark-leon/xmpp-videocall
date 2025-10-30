package com.example.xmppvideocall

import android.util.Log
import org.webrtc.SessionDescription

object SDPJingleConverter {

    private const val TAG = "SDPJingleConverter"

    /**
     * Convert WebRTC SDP to Jingle XML format
     */
    fun sdpToJingle(sdp: String, sessionId: String): JingleMessage {
        val jingleMessage = JingleMessage().apply {
            sid = sessionId
            action = "session-initiate" // or "session-accept" based on context
        }

        val lines = sdp.split("\r\n")
        var currentMedia: String? = null
        var currentContent: Content? = null
        var currentTransport: Transport? = null
        var iceUfrag = ""
        var icePwd = ""
        var fingerprint = ""

        lines.forEach { line ->
            when {
                line.startsWith("m=") -> {
                    // Save previous content if exists
                    currentContent?.let { content ->
                        currentTransport?.let { transport ->
                            transport.ufrag = iceUfrag
                            transport.pwd = icePwd
                            transport.fingerprint = fingerprint
                            content.transport = transport
                        }
                        jingleMessage.addContent(content)
                    }

                    // Start new content
                    val parts = line.substring(2).split(" ")
                    currentMedia = parts[0] // audio or video

                    currentContent = Content().apply {
                        name = jingleMessage.contents.size.toString()
                        creator = "initiator"
                        description = Description().apply {
                            media = currentMedia ?: "audio"
                        }
                    }
                    currentTransport = Transport()
                }

                line.startsWith("a=rtpmap:") -> {
                    // Parse RTP map: a=rtpmap:111 opus/48000/2
                    val rtpMap = line.substring(9)
                    val parts = rtpMap.split(" ")
                    val id = parts[0]
                    val codecInfo = parts.getOrNull(1)?.split("/")

                    if (codecInfo != null && codecInfo.isNotEmpty()) {
                        val payloadType = PayloadType().apply {
                            this.id = id
                            this.name = codecInfo[0]
                            this.clockrate = codecInfo.getOrNull(1) ?: ""
                            this.channels = codecInfo.getOrNull(2) ?: ""
                        }
                        currentContent?.description?.addPayloadType(payloadType)
                    }
                }

                line.startsWith("a=fmtp:") -> {
                    // Parse format parameters: a=fmtp:111 minptime=10;useinbandfec=1
                    val fmtp = line.substring(7)
                    val parts = fmtp.split(" ", limit = 2)
                    val payloadId = parts[0]
                    val params = parts.getOrNull(1)?.split(";") ?: emptyList()

                    val payloadType = currentContent?.description?.payloadTypes?.find {
                        it.id == payloadId
                    }

                    params.forEach { param ->
                        val keyValue = param.split("=")
                        if (keyValue.size == 2) {
                            payloadType?.addParameter(Parameter(keyValue[0], keyValue[1]))
                        }
                    }
                }

                line.startsWith("a=ice-ufrag:") -> {
                    iceUfrag = line.substring(12)
                }

                line.startsWith("a=ice-pwd:") -> {
                    icePwd = line.substring(10)
                }

                line.startsWith("a=fingerprint:") -> {
                    // a=fingerprint:sha-256 XX:XX:XX...
                    val parts = line.substring(14).split(" ", limit = 2)
                    fingerprint = parts.getOrNull(1) ?: ""
                }

                line.startsWith("a=candidate:") -> {
                    // Parse ICE candidate
                    // a=candidate:foundation component protocol priority ip port typ type
                    val candidateStr = line.substring(12)
                    val parts = candidateStr.split(" ")

                    if (parts.size >= 8) {
                        val candidate = Candidate().apply {
                            foundation = parts[0]
                            component = parts[1]
                            protocol = parts[2]
                            priority = parts[3].toIntOrNull() ?: 0
                            ip = parts[4]
                            port = parts[5].toIntOrNull() ?: 0
                            // parts[6] is "typ"
                            type = parts.getOrNull(7) ?: ""
                            id = "candidate_${System.currentTimeMillis()}"
                        }
                        currentTransport?.addCandidate(candidate)
                    }
                }
            }
        }

        // Save last content
        currentContent?.let { content ->
            currentTransport?.let { transport ->
                transport.ufrag = iceUfrag
                transport.pwd = icePwd
                transport.fingerprint = fingerprint
                content.transport = transport
            }
            jingleMessage.addContent(content)
        }

        return jingleMessage
    }

    /**
     * Convert Jingle XML to WebRTC SDP
     * (This is already implemented in your SDPBuilder.kt)
     */
    fun jingleToSdp(jingleMessage: JingleMessage): String {
        return SDPBuilder.jingleToSdp(jingleMessage)
    }

    /**
     * Extract ICE candidates from SDP
     */
    fun extractIceCandidatesFromSdp(sdp: String): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        val lines = sdp.split("\r\n")

        lines.forEach { line ->
            if (line.startsWith("a=candidate:")) {
                val candidateStr = line.substring(12)
                val parts = candidateStr.split(" ")

                if (parts.size >= 8) {
                    val candidate = Candidate().apply {
                        foundation = parts[0]
                        component = parts[1]
                        protocol = parts[2]
                        priority = parts[3].toIntOrNull() ?: 0
                        ip = parts[4]
                        port = parts[5].toIntOrNull() ?: 0
                        type = parts.getOrNull(7) ?: ""
                        id = "candidate_${System.currentTimeMillis()}_${candidates.size}"
                    }
                    candidates.add(candidate)
                }
            }
        }

        return candidates
    }

    /**
     * Create ICE candidate SDP line from Candidate object
     */
    fun candidateToSdpLine(candidate: Candidate): String {
        return "candidate:${candidate.foundation} ${candidate.component} " +
                "${candidate.protocol} ${candidate.priority} ${candidate.ip} " +
                "${candidate.port} typ ${candidate.type}"
    }
}