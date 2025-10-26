package com.example.xmppvideocall



import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory


object JingleIQParser {

    fun parseJingleIQ(xmlContent: String): JingleMessage {
        val jingleMessage = JingleMessage()
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(xmlContent)))

        val jingleElement = doc.documentElement
        jingleMessage.sid = jingleElement.getAttribute("sid")
        jingleMessage.action = jingleElement.getAttribute("action")

        val contentList = jingleElement.getElementsByTagName("content")

        for (i in 0 until contentList.length) {
            val contentElement = contentList.item(i) as Element
            val content = Content().apply {
                name = contentElement.getAttribute("name")
                creator = contentElement.getAttribute("creator")
            }

            // Parsing description element
            val descriptionList = contentElement.getElementsByTagNameNS(
                "urn:xmpp:jingle:apps:rtp:1",
                "description"
            )

            if (descriptionList.length > 0) {
                val descriptionElement = descriptionList.item(0) as Element
                val description = Description().apply {
                    media = descriptionElement.getAttribute("media")
                }

                // Parsing payload types
                val payloadTypeList = descriptionElement.getElementsByTagName("payload-type")
                for (j in 0 until payloadTypeList.length) {
                    val payloadElement = payloadTypeList.item(j) as Element
                    val payloadType = PayloadType().apply {
                        name = payloadElement.getAttribute("name")
                        clockrate = payloadElement.getAttribute("clockrate")
                        id = payloadElement.getAttribute("id")
                    }

                    // Parsing parameters within the payload-type
                    val parameterList = payloadElement.getElementsByTagName("parameter")
                    for (k in 0 until parameterList.length) {
                        val parameterElement = parameterList.item(k) as Element
                        val parameter = Parameter(
                            name = parameterElement.getAttribute("name"),
                            value = parameterElement.getAttribute("value")
                        )
                        payloadType.addParameter(parameter)
                    }

                    description.addPayloadType(payloadType)
                }
                content.description = description
            }

            // Parsing transport element
            val transportList = contentElement.getElementsByTagNameNS(
                "urn:xmpp:jingle:transports:ice-udp:1",
                "transport"
            )

            if (transportList.length > 0) {
                val transportElement = transportList.item(0) as Element
                val transport = getTransport(transportElement)
                content.transport = transport
            }

            jingleMessage.addContent(content)
        }

        // Parsing group element
        val groupList = jingleElement.getElementsByTagNameNS(
            "urn:xmpp:jingle:apps:grouping:0",
            "group"
        )

        if (groupList.length > 0) {
            val groupElement = groupList.item(0) as Element
            val group = Group().apply {
                semantics = groupElement.getAttribute("semantics")
            }
            jingleMessage.group = group
        }

        return jingleMessage
    }

    private fun getTransport(transportElement: Element): Transport {
        val transport = Transport().apply {
            ufrag = transportElement.getAttribute("ufrag")
            pwd = transportElement.getAttribute("pwd")
        }

        // Parse fingerprint
        val fingerprintList = transportElement.getElementsByTagNameNS(
            "urn:xmpp:jingle:apps:dtls:0",
            "fingerprint"
        )
        if (fingerprintList.length > 0) {
            val fingerprintElement = fingerprintList.item(0) as Element
            transport.fingerprint = fingerprintElement.textContent
        }

        // Parse ICE candidates
        val candidateNodes = transportElement.getElementsByTagNameNS(
            "urn:xmpp:jingle:transports:ice-udp:1",
            "candidate"
        )

        for (j in 0 until candidateNodes.length) {
            val candidateElement = candidateNodes.item(j) as Element
            val candidate = Candidate().apply {
                id = candidateElement.getAttribute("id")
                foundation = candidateElement.getAttribute("foundation")
                ip = candidateElement.getAttribute("ip")
                port = candidateElement.getAttribute("port").toIntOrNull() ?: 0
                protocol = candidateElement.getAttribute("protocol")
                type = candidateElement.getAttribute("type")
                priority = candidateElement.getAttribute("priority").toIntOrNull() ?: 0
                component = candidateElement.getAttribute("component")
                generation = candidateElement.getAttribute("generation").toIntOrNull() ?: 0
            }
            transport.addCandidate(candidate)
        }

        return transport
    }
}