package com.example.xmppvideocall

import kotlinx.coroutines.*
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Localpart
import com.example.xmppvideocall.Contact
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jxmpp.jid.Jid

class XmppConnectionManager {

    private var connection: AbstractXMPPConnection? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentUsername: String? = null

    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onContactsUpdated: ((List<Contact>) -> Unit)? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, ERROR
    }

    private val defaultContacts = mapOf(
        "leion" to listOf(
            Contact(
                jid = JidCreate.from("rafin@ejabberd.arafinahmed.com"),
                name = "Rafin",
                status = "Available"
            )
        ),
        "rafin" to listOf(
            Contact(
                jid = JidCreate.from("leion@ejabberd.arafinahmed.com"),
                name = "Leion",
                status = "Available"
            )
        )
    )

    private val connectionListener = object : ConnectionListener {
        override fun connected(connection: XMPPConnection) {
            onConnectionStateChanged?.invoke(ConnectionState.CONNECTED)
        }

        override fun authenticated(connection: XMPPConnection, resumed: Boolean) {
            onConnectionStateChanged?.invoke(ConnectionState.AUTHENTICATED)
            loadContacts()
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
            currentUsername = username.lowercase()

            val config = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain("ejabberd.arafinahmed.com")
                .setHost("ejabberd.arafinahmed.com")
                .setPort(5222)
                .setUsernameAndPassword(username, password)
                .setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
                .setKeystoreType(null)
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
        connection?.disconnect()
        connection = null
        currentUsername = null
    }

    fun getConnection(): XMPPConnection? = connection

    private fun loadContacts() {
        scope.launch {
            try {
                val contacts = mutableListOf<Contact>()

                // First, add default contacts based on username
                currentUsername?.let { username ->
                    val defaults = defaultContacts[username]
                    if (defaults != null) {
                        contacts.addAll(defaults)
                    }
                }

                // Then try to load from roster if connection is available
                val currentConnection = connection
                if (currentConnection != null && currentConnection.isConnected) {
                    try {
                        val roster = Roster.getInstanceFor(currentConnection)
                        if (!roster.isLoaded) {
                            roster.reloadAndWait()
                        }

                        val rosterContacts = roster.entries.mapNotNull { entry ->
                            try {
                                Contact(
                                    jid = entry.jid,
                                    name = entry.name ?: entry.jid.localpartOrNull?.toString() ?: "Unknown",
                                    status = if (roster.getPresence(entry.jid).isAvailable) "Available" else "Offline"
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }

                        // Add roster contacts that aren't already in default list
                        rosterContacts.forEach { rosterContact ->
                            if (contacts.none { it.jid.toString() == rosterContact.jid.toString() }) {
                                contacts.add(rosterContact)
                            }
                        }
                    } catch (e: Exception) {
                        // If roster loading fails, continue with default contacts
                        e.printStackTrace()
                    }
                }

                // If no contacts found at all, use default contacts for current user
                if (contacts.isEmpty()) {
                    currentUsername?.let { username ->
                        defaultContacts[username]?.let { defaults ->
                            contacts.addAll(defaults)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    onContactsUpdated?.invoke(contacts)
                }
            } catch (e: Exception) {
                // Final fallback - use default contacts
                currentUsername?.let { username ->
                    defaultContacts[username]?.let { defaults ->
                        withContext(Dispatchers.Main) {
                            onContactsUpdated?.invoke(defaults)
                        }
                    }
                }
                e.printStackTrace()
            }
        }
    }

    fun cleanup() {
        scope.cancel()
        disconnect()
    }
}