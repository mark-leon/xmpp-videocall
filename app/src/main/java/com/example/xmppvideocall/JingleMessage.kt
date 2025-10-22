package com.example.xmppvideocall

data class JingleMessage(
    var sid: String = "",
    var action: String = "",
    var contents: MutableList<Content> = mutableListOf(),
    var group: Group? = null
) {
    fun addContent(content: Content) {
        contents.add(content)
    }
}