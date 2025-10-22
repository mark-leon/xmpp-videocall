package com.example.xmppvideocall

data class Transport(
    var ufrag: String = "",
    var pwd: String = "",
    var fingerprint: String = "",
    var candidates: MutableList<Candidate> = mutableListOf()
) {
    fun addCandidate(candidate: Candidate) {
        candidates.add(candidate)
    }

    fun hasCandidates(): Boolean = candidates.isNotEmpty()
}