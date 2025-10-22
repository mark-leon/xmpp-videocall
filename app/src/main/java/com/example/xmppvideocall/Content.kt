package com.example.xmppvideocall

data class Content(
    var name: String = "",
    var creator: String = "",
    var description: Description? = null,
    var transport: Transport? = null
)