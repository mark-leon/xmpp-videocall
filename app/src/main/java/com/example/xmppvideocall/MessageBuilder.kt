package com.example.xmppvideocall

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jxmpp.jid.impl.JidCreate

object MessageBuilder {

    fun buildRingingMessage(toJid: String, id: String): Message {
        return Message().apply {
            type = Message.Type.chat
            to = JidCreate.from(toJid)  // ✅ Convert String → Jid

            // Create the <ringing> element
            val ringingElement = StandardExtensionElement.builder("ringing", "urn:xmpp:jingle-message:0")
                .addAttribute("id", id)
                .build()
            addExtension(ringingElement)

            // Create the <store> element
            val storeElement = StandardExtensionElement.builder("store", "urn:xmpp:hints")
                .build()
            addExtension(storeElement)
        }
    }

    fun buildProposeMessage(toJid: String, id: String): Message {
        return Message().apply {
            type = Message.Type.chat
            to = JidCreate.from(toJid)
            stanzaId = "jm-propose-$id"

            // Create the <propose> element
            val proposeBuilder = StandardExtensionElement.builder("propose", "urn:xmpp:jingle-message:0")
                .addAttribute("id", id)

            // Create the <description> element and add it to <propose>
            val descriptionElement = StandardExtensionElement.builder("description", "urn:xmpp:jingle:apps:rtp:1")
                .addAttribute("media", "audio")
                .build()

            proposeBuilder.addElement(descriptionElement)
            val proposeElement = proposeBuilder.build()
            addExtension(proposeElement)

            // Create and add the <request> element
            val requestElement = StandardExtensionElement.builder("request", "urn:xmpp:receipts")
                .build()
            addExtension(requestElement)

            // Create and add the <store> element
            val storeElement = StandardExtensionElement.builder("store", "urn:xmpp:hints")
                .build()
            addExtension(storeElement)
        }
    }

    fun buildProceedMessage(toJid: String, fromJid: String, id: String, deviceId: String = "1439082960"): Message {
        return Message().apply {
            type = Message.Type.chat
            to = JidCreate.from(toJid)
            from =  JidCreate.from(fromJid)
            stanzaId = id

            // Create the <device> element
            val device = StandardExtensionElement.builder("device", "http://gultsch.de/xmpp/drafts/omemo/dlts-srtp-verification")
                .addAttribute("id", deviceId)
                .build()

            // Create the <proceed> element with nested device
            val proceed = StandardExtensionElement.builder("proceed", "urn:xmpp:jingle-message:0")
                .addAttribute("id", id)
                .addElement(device)
                .build()

            addExtension(proceed)

            // Add the <store> element
            val store = StandardExtensionElement.builder("store", "urn:xmpp:hints")
                .build()
            addExtension(store)
        }
    }

    fun buildActiveMessage(toJid: String, id: String): Message {
        return Message().apply {
            type = Message.Type.chat
            to = JidCreate.from(toJid)

            val activeElement = StandardExtensionElement.builder("active", "urn:xmpp:jingle-message:0")
                .addAttribute("id", id)
                .build()
            addExtension(activeElement)
        }
    }

    fun buildAcceptMessage(toJid: String, id: String): Message {
        return Message().apply {
            type = Message.Type.chat
            to = JidCreate.from(toJid)

            val acceptElement = StandardExtensionElement.builder("accept", "urn:xmpp:jingle-message:0")
                .addAttribute("id", id)
                .build()
            addExtension(acceptElement)

            val storeElement = StandardExtensionElement.builder("store", "urn:xmpp:hints")
                .build()
            addExtension(storeElement)
        }
    }
}