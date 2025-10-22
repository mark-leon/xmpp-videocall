package com.example.xmppvideocall

data class Candidate(
    var id: String = "",
    var foundation: String = "",
    var ip: String = "",
    var port: Int = 0,
    var protocol: String = "",
    var type: String = "",
    var priority: Int = 0,
    var component: String = "",
    var generation: Int = 0
)