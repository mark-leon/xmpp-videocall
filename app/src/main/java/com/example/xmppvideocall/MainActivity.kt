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
import com.example.xmppvideocall.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val xmppManager = XmppConnectionManager()

    private val PERMISSIONS_REQUEST_CODE = 100
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set the static reference
        MainActivity.xmppConnectionManager = xmppManager

        checkPermissions()
        setupUI()
        setupConnectionManager()
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
        binding.loginButton.setOnClickListener {
            val username = binding.usernameInput.text.toString()
            val password = binding.passwordInput.text.toString()
            val server = binding.serverInput.text.toString()
            val port = binding.portInput.text.toString().toIntOrNull() ?: 5222

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
                        binding.statusText.text = getString(R.string.connecting)
                        binding.progressBar.visibility = View.VISIBLE
                        binding.loginButton.isEnabled = false
                    }
                    XmppConnectionManager.ConnectionState.AUTHENTICATED -> {
                        binding.statusText.text = getString(R.string.connected)
                        binding.progressBar.visibility = View.GONE
                        binding.loginButton.isEnabled = true

                        // Navigate to contacts
                        val intent = Intent(this, ContactsActivity::class.java)
                        startActivity(intent)
                    }
                    XmppConnectionManager.ConnectionState.ERROR -> {
                        binding.statusText.text = "Connection failed"
                        binding.progressBar.visibility = View.GONE
                        binding.loginButton.isEnabled = true
                        Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
                    }
                    XmppConnectionManager.ConnectionState.DISCONNECTED -> {
                        binding.statusText.text = getString(R.string.disconnected)
                        binding.progressBar.visibility = View.GONE
                        binding.loginButton.isEnabled = true
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
            MainActivity.xmppConnectionManager = null
        }
    }

    companion object {
        var xmppConnectionManager: XmppConnectionManager? = null
    }
}