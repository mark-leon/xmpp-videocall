package com.example.xmppcall.handler

import android.util.Log
import com.example.xmppcall.utils.SDPBuilder
import com.example.xmppvideocall.Candidate
import com.example.xmppvideocall.Transport
import com.example.xmppvideocall.parser.JingleIQParser
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.UnparsedIQ

object JingleMessageHandler {

    private const val TAG = "JingleMessageHandler"

    interface JingleCallback {
        fun onSdpReceived(sdp: String, action: String)
        fun onIceCandidatesReceived(candidates: List<Candidate>)
    }

    private var callback: JingleCallback? = null

    fun setCallback(callback: JingleCallback) {
        this.callback = callback
    }

    fun handleJingleIqMessage(message: IQ): String {
        try {
            val jingleMessage = JingleIQParser.parseJingleIQ(
                (message as UnparsedIQ).content.toString()
            )

            val action = jingleMessage.action

            when (action) {
                "session-initiate", "session-accept" -> {
                    // Handle SDP exchange
                    val sdp = SDPBuilder.jingleToSdp(jingleMessage)
                    Log.d(TAG, "Received SDP:\n$sdp")
                    callback?.onSdpReceived(sdp, action)
                }
                "transport-info" -> {
                    // Handle ICE candidates
                    val allCandidates = mutableListOf<Candidate>()
                    jingleMessage.contents.forEach { content ->
                        content.transport?.let { transport ->
                            if (transport.hasCandidates()) {
                                allCandidates.addAll(transport.candidates)
                                handleIceCandidates(transport)
                            }
                        }
                    }
                    if (allCandidates.isNotEmpty()) {
                        callback?.onIceCandidatesReceived(allCandidates)
                    }
                    Log.d(TAG, "Received ICE candidates")
                }
                else -> {
                    Log.w(TAG, "Unknown Jingle action: $action")
                }
            }

            return action
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Jingle IQ message", e)
            throw e
        }
    }

    private fun handleIceCandidates(transport: Transport) {
        transport.candidates.forEach { candidate ->
            Log.d(TAG, "Handling ICE Candidate:")
            Log.d(TAG, "  IP: ${candidate.ip}")
            Log.d(TAG, "  Port: ${candidate.port}")
            Log.d(TAG, "  Protocol: ${candidate.protocol}")
            Log.d(TAG, "  Type: ${candidate.type}")
        }
    }
}