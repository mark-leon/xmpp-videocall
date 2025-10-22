package com.example.xmppcall.utils

import com.example.xmppvideocall.JingleMessage

object SDPBuilder {

    fun jingleToSdp(jingleMessage: JingleMessage): String {
        val sdp = StringBuilder()

        // SDP Version
        sdp.append("v=0\r\n")

        // Origin line (o=): contains session ID
        val sessionId = jingleMessage.sid
        sdp.append("o=- $sessionId 2 IN IP4 0.0.0.0\r\n")

        // Session Name (s=)
        sdp.append("s=Jingle Session\r\n")

        // Time description (t=)
        sdp.append("t=0 0\r\n")

        // Grouping (a=group:BUNDLE)
        jingleMessage.group?.let {
            sdp.append("a=group:BUNDLE ")
            jingleMessage.contents.forEach { content ->
                sdp.append("${content.name} ")
            }
            sdp.append("\r\n")
        }

        // Iterate over contents
        jingleMessage.contents.forEach { content ->
            val description = content.description ?: return@forEach
            val mediaType = description.media

            sdp.append("m=$mediaType 9 UDP/TLS/RTP/SAVPF")

            // Add payload types
            description.payloadTypes.forEach { payloadType ->
                sdp.append(" ${payloadType.id}")
            }
            sdp.append("\r\n")

            // Connection information (c=)
            sdp.append("c=IN IP4 0.0.0.0\r\n")

            // RTP map for each payload type
            description.payloadTypes.forEach { payloadType ->
                sdp.append("a=rtpmap:${payloadType.id} ${payloadType.name}/${payloadType.clockrate}\r\n")

                // Additional payload-specific parameters (a=fmtp)
                payloadType.parameters.forEach { parameter ->
                    sdp.append("a=fmtp:${payloadType.id} ${parameter.name}=${parameter.value}\r\n")
                }
            }

            // RTCP-MUX if available
            if (description.media == "audio" || description.media == "video") {
                sdp.append("a=rtcp-mux\r\n")
            }

            // ICE and DTLS fingerprints
            content.transport?.let { transport ->
                sdp.append("a=ice-ufrag:${transport.ufrag}\r\n")
                sdp.append("a=ice-pwd:${transport.pwd}\r\n")
                sdp.append("a=fingerprint:sha-256 ${transport.fingerprint}\r\n")
                sdp.append("a=setup:actpass\r\n")
            }
        }

        return sdp.toString()
    }
}