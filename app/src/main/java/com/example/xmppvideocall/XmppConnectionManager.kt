package com.example.xmppvideocall

import kotlinx.coroutines.*
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener

import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jxmpp.jid.Jid

class XmppConnectionManager {

    private var connection: XMPPConnection? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, ERROR
    }

    private val connectionListener = object : ConnectionListener {
        override fun connected(connection: XMPPConnection) {
            onConnectionStateChanged?.invoke(ConnectionState.CONNECTED)
        }

        override fun authenticated(connection: XMPPConnection, resumed: Boolean) {
            onConnectionStateChanged?.invoke(ConnectionState.AUTHENTICATED)
        }

        override fun connectionClosed() {
            onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
        }

        override fun connectionClosedOnError(e: Exception) {
            onConnectionStateChanged?.invoke(ConnectionState.ERROR)
        }
    }


    suspend fun connect(
        username: String,
        password: String,
        server: String,
        port: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onConnectionStateChanged?.invoke(ConnectionState.CONNECTING)

            val config = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain("ejabberd.arafinahmed.com")
                .setHost("ejabberd.arafinahmed.com")
                .setPort(5222)
                .setUsernameAndPassword(username, password)
                .setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
                .setCompressionEnabled(false)
                .build()

            val newConnection = XMPPTCPConnection(config)
            newConnection.addConnectionListener(connectionListener)

            newConnection.connect()
            newConnection.login()

            // Store the connection instance
            connection = newConnection

            Result.success(Unit)
        } catch (e: Exception) {
            onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            Result.failure(e)
        }
    }

    fun disconnect() {
//        connection?.disconnect()
        connection = null
    }

    fun getConnection(): XMPPConnection? = connection



    fun cleanup() {
        scope.cancel()
        disconnect()
    }
}