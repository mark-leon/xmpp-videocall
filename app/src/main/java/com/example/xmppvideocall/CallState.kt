package com.example.xmppvideocall

import org.jxmpp.jid.Jid

data class Contact(
    val jid: Jid,
    val name: String,
    val status: String = "Available"
)

enum class CallState {
    IDLE,
    INITIATING,
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDED
}
