package com.example.xmppvideocall

data class Description(
    var media: String = "",
    var payloadTypes: MutableList<PayloadType> = mutableListOf()
) {
    fun addPayloadType(payloadType: PayloadType) {
        payloadTypes.add(payloadType)
    }
}
