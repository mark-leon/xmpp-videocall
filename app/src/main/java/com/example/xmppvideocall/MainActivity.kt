package com.example.xmppvideocall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.xmppvideocall.databinding.ActivityMainBinding
import com.example.xmppcall.handler.JingleMessageHandler
import org.jivesoftware.smack.packet.Message

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var xmppManager: XmppConnectionManager? = null
    private var currentCallId: String? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupUI()
        setupJingleHandler()
    }

    private fun checkPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            connectToXmpp()
        }

        binding.btnDisconnect.setOnClickListener {
            disconnectFromXmpp()
        }

        binding.btnAnswer.setOnClickListener {
            answerCall()
        }

        binding.btnReject.setOnClickListener {
            rejectCall()
        }

        binding.btnEndCall.setOnClickListener {
            endCall()
        }

        updateUIState(false)
    }

    private fun setupJingleHandler() {
        JingleMessageHandler.setCallback(object : JingleMessageHandler.JingleCallback {
            override fun onSdpReceived(sdp: String, action: String) {
                runOnUiThread {
                    binding.tvStatus.text = "SDP Received: $action"
                    binding.tvSdpInfo.text = sdp
                }
            }

            override fun onIceCandidatesReceived(candidates: List<Candidate>) {
                runOnUiThread {
                    val candidateInfo = candidates.joinToString("\n") {
                        "IP: ${it.ip}:${it.port} Type: ${it.type}"
                    }
                    binding.tvIceCandidates.text = candidateInfo
                }
            }
        })
    }

    private fun connectToXmpp() {
        val username = binding.etUsername.text.toString()
        val password = binding.etPassword.text.toString()
        val domain = binding.etDomain.text.toString()

        if (username.isEmpty() || password.isEmpty() || domain.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvStatus.text = "Connecting..."

        xmppManager = XmppConnectionManager(username, password, domain)
        xmppManager?.setConnectionListener(object : XmppConnectionManager.ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    binding.tvStatus.text = "Connected"
                    updateUIState(true)
                    Toast.makeText(this@MainActivity, "Connected successfully", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    binding.tvStatus.text = "Disconnected"
                    updateUIState(false)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    binding.tvStatus.text = "Error: $error"
                    Toast.makeText(this@MainActivity, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }

            override fun onMessageReceived(message: Message, elementType: String) {
                runOnUiThread {
                    handleIncomingMessage(message, elementType)
                }
            }
        })

        xmppManager?.connect()
    }

    private fun disconnectFromXmpp() {
        xmppManager?.disconnect()
        xmppManager = null
        binding.tvStatus.text = "Disconnected"
        updateUIState(false)
    }

    private fun handleIncomingMessage(message: Message, elementType: String) {
        when (elementType) {
            "propose" -> {
                val proposeElement = message.getExtension<org.jivesoftware.smack.packet.StandardExtensionElement>(
                    "propose",
                    "urn:xmpp:jingle-message:0"
                )

                currentCallId = proposeElement?.getAttributeValue("id")

                binding.tvStatus.text = "Incoming call from: ${message.from}"
                binding.llIncomingCall.visibility = android.view.View.VISIBLE
                binding.tvCallerInfo.text = "Caller: ${message.from}"
            }

            "ringing" -> {
                binding.tvStatus.text = "Call ringing..."
            }
            "accept" -> {
                binding.tvStatus.text = "Call accepted"
                binding.llIncomingCall.visibility = android.view.View.GONE
                binding.llCallControls.visibility = android.view.View.VISIBLE
            }
            "proceed" -> {
                binding.tvStatus.text = "Call proceeding..."
            }
        }
    }

    private fun answerCall() {
        currentCallId?.let { callId ->
            xmppManager?.sendAcceptResponse(
                binding.tvCallerInfo.text.toString().removePrefix("Caller: "),
                callId
            )
            binding.llIncomingCall.visibility = android.view.View.GONE
            binding.llCallControls.visibility = android.view.View.VISIBLE
            binding.tvStatus.text = "Call in progress"
        }
    }

    private fun rejectCall() {
        currentCallId = null
        binding.llIncomingCall.visibility = android.view.View.GONE
        binding.tvStatus.text = "Call rejected"
    }

    private fun endCall() {
        currentCallId = null
        binding.llCallControls.visibility = android.view.View.GONE
        binding.tvStatus.text = "Call ended"
        binding.tvSdpInfo.text = ""
        binding.tvIceCandidates.text = ""
    }

    private fun updateUIState(connected: Boolean) {
        binding.btnConnect.isEnabled = !connected
        binding.btnDisconnect.isEnabled = connected
        binding.etUsername.isEnabled = !connected
        binding.etPassword.isEnabled = !connected
        binding.etDomain.isEnabled = !connected
    }

    override fun onDestroy() {
        super.onDestroy()
        xmppManager?.disconnect()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Permissions required for audio calls", Toast.LENGTH_LONG).show()
            }
        }
    }
}