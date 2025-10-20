package com.example.xmppvideocall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etServer: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnConnect: Button
    private lateinit var etCalleeJid: EditText
    private lateinit var btnAudioCall: Button
    private lateinit var btnVideoCall: Button

    private val xmppManager = XmppConnectionManager()
    private var isConnected = false

    companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val EXTRA_CALLEE_JID = "callee_jid"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_HAS_VIDEO = "has_video"
        const val EXTRA_IS_INCOMING = "is_incoming"
        const val EXTRA_REMOTE_SDP = "remote_sdp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupXmppCallbacks()
        checkPermissions()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        etServer = findViewById(R.id.etServer)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etServer.setText("ejabberd.arafinahmed.com")
        etUsername.setText("leion")
        etPassword.setText("123")

        btnConnect = findViewById(R.id.btnConnect)
        etCalleeJid = findViewById(R.id.etCalleeJid)
        btnAudioCall = findViewById(R.id.btnAudioCall)
        btnVideoCall = findViewById(R.id.btnVideoCall)

        btnConnect.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        btnAudioCall.setOnClickListener {
            startCall(false)
        }

        btnVideoCall.setOnClickListener {
            startCall(true)
        }
    }

    private fun setupXmppCallbacks() {
        xmppManager.onConnectionChanged = { connected ->
            runOnUiThread {
                isConnected = connected
                updateConnectionUI(connected)
            }
        }

        xmppManager.onIncomingCall = { fromJid, sessionId, hasVideo ->
            runOnUiThread {
                showIncomingCallDialog(fromJid, sessionId, hasVideo)
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
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
                Toast.makeText(this, "Permissions required for calling", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun connect() {
        val server = etServer.text.toString().trim()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Status: Connecting..."
        xmppManager.connect(server, username, password)
    }

    private fun disconnect() {
        xmppManager.disconnect()
        isConnected = false
        updateConnectionUI(false)
    }

    private fun updateConnectionUI(connected: Boolean) {
        if (connected) {
            tvStatus.text = "Status: Connected (${xmppManager.getOwnJid()})"
            btnConnect.text = "Disconnect"
            etCalleeJid.isEnabled = true
            btnAudioCall.isEnabled = true
            btnVideoCall.isEnabled = true

            etServer.isEnabled = false
            etUsername.isEnabled = false
            etPassword.isEnabled = false
        } else {
            tvStatus.text = "Status: Disconnected"
            btnConnect.text = "Connect"
            etCalleeJid.isEnabled = false
            btnAudioCall.isEnabled = false
            btnVideoCall.isEnabled = false

            etServer.isEnabled = true
            etUsername.isEnabled = true
            etPassword.isEnabled = true
        }
    }

    private fun startCall(hasVideo: Boolean) {
        val calleeJid = etCalleeJid.text.toString().trim()

        if (calleeJid.isEmpty()) {
            Toast.makeText(this, "Please enter callee JID", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(EXTRA_CALLEE_JID, calleeJid)
            putExtra(EXTRA_HAS_VIDEO, hasVideo)
            putExtra(EXTRA_IS_INCOMING, false)
        }
        startActivity(intent)
    }

    private fun showIncomingCallDialog(fromJid: String, sessionId: String, hasVideo: Boolean) {
        val callType = if (hasVideo) "Video" else "Audio"

        AlertDialog.Builder(this)
            .setTitle("Incoming $callType Call")
            .setMessage("From: $fromJid")
            .setPositiveButton("Accept") { dialog, _ ->
                val intent = Intent(this, CallActivity::class.java).apply {
                    putExtra(EXTRA_CALLEE_JID, fromJid)
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_HAS_VIDEO, hasVideo)
                    putExtra(EXTRA_IS_INCOMING, true)
                }
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton("Decline") { dialog, _ ->
                xmppManager.sendSessionTerminate(fromJid, sessionId)
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        xmppManager.disconnect()
    }
}
