package com.example.xmppvideocall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.xmppvideocall.XmppConnectionManager
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import android.widget.Button
import android.widget.TextView
import android.widget.ProgressBar

class MainActivity : AppCompatActivity() {

    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var serverInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val xmppManager = XmppConnectionManager()

    private val PERMISSIONS_REQUEST_CODE = 100
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        setupUI()
        setupConnectionManager()
    }

    private fun initViews() {
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        serverInput = findViewById(R.id.serverInput)
        portInput = findViewById(R.id.portInput)
        loginButton = findViewById(R.id.loginButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun checkPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private fun setupUI() {
        usernameInput.setText("leion")
        passwordInput.setText("123")

        loginButton.setOnClickListener {
            val username = usernameInput.text.toString()
            val password = passwordInput.text.toString()
            val server = serverInput.text.toString()
            val port = portInput.text.toString().toIntOrNull() ?: 5222

            if (username.isEmpty() || password.isEmpty() || server.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            connectToXmpp(username, password, server, port)
        }
    }

    private fun setupConnectionManager() {
        xmppManager.onConnectionStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    XmppConnectionManager.ConnectionState.CONNECTING -> {
                        statusText.text = getString(R.string.connecting)
                        progressBar.visibility = View.VISIBLE
                        loginButton.isEnabled = false
                    }
                    XmppConnectionManager.ConnectionState.AUTHENTICATED -> {
                        statusText.text = getString(R.string.connected)
                        progressBar.visibility = View.GONE
                        loginButton.isEnabled = true

                        xmppConnectionManager = xmppManager
                        val intent = Intent(this, HomeActivity::class.java)
                        intent.putExtra("USERNAME", usernameInput.text.toString())
                        startActivity(intent)
                    }
                    XmppConnectionManager.ConnectionState.ERROR -> {
                        statusText.text = "Connection failed"
                        progressBar.visibility = View.GONE
                        loginButton.isEnabled = true
                        Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
                    }
                    XmppConnectionManager.ConnectionState.DISCONNECTED -> {
                        statusText.text = getString(R.string.disconnected)
                        progressBar.visibility = View.GONE
                        loginButton.isEnabled = true
                    }
                    else -> {}
                }
            }
        }
    }

    private fun connectToXmpp(username: String, password: String, server: String, port: Int) {
        lifecycleScope.launch {
            val result = xmppManager.connect(username, password, server, port)

            if (result.isFailure) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            xmppManager.cleanup()
        }
    }

    companion object {
        var xmppConnectionManager: XmppConnectionManager? = null
    }
}
