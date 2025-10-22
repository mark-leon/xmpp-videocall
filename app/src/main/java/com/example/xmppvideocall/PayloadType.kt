package com.example.xmppvideocall

import com.example.xmppcall.model.Parameter

data class PayloadType(
    var name: String = "",
    var clockrate: String = "",
    var id: String = "",
    var channels: String = "",
    var parameters: MutableList<Parameter> = mutableListOf()
) {
    fun addParameter(parameter: Parameter) {
        parameters.add(parameter)
    }
}