package com.jpn.app.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives socket file descriptors from the TURN Go child process over an
 * abstract Unix-domain socket and asks the active VpnService to protect them.
 *
 * The descriptor is transferred with SCM_RIGHTS. Android gives this process
 * its own descriptor for the same kernel socket, so protect() can mark that
 * socket without excluding the whole JPN application from the VPN.
 */
internal class TurnSocketProtector(
    private val vpnService: VpnService
) : Closeable {

    companion object {
        // Must match the Go client's JPN_TURN_PROTECT_SOCKET value: @jpn.turn.protect
        const val SOCKET_NAME = "jpn.turn.protect"
    }

    private val closed = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "TURN socket protector is closed" }
        if (server != null) return

        val localServer = LocalServerSocket(
            LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
        )
        server = localServer

        acceptThread = Thread({
            while (!closed.get()) {
                val client = try {
                    localServer.accept()
                } catch (error: IOException) {
                    if (!closed.get()) throw error
                    break
                }

                Thread({
                    handleClient(client)
                }, "JPN-TURN-Protect-Request").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "JPN-TURN-Protect-Acceptor").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleClient(client: LocalSocket) {
        client.use { socket ->
            var receivedDescriptors = emptyArray<java.io.FileDescriptor>()
            try {
                socket.soTimeout = 5_000

                // The abstract socket has no filesystem permissions. Restrict
                // descriptor-protection requests to JPN's own UID (including
                // its child processes).
                if (socket.peerCredentials.uid != Process.myUid()) {
                    socket.outputStream.write(0)
                    socket.outputStream.flush()
                    return
                }

                if (socket.inputStream.read() < 0) return
                receivedDescriptors = socket.ancillaryFileDescriptors ?: emptyArray()
                val received = receivedDescriptors.firstOrNull()
                if (received == null) {
                    socket.outputStream.write(0)
                    socket.outputStream.flush()
                    return
                }

                val protected = ParcelFileDescriptor.dup(received).use { duplicate ->
                    vpnService.protect(duplicate.fd)
                }

                socket.outputStream.write(if (protected) 1 else 0)
                socket.outputStream.flush()
            } catch (_: Exception) {
                runCatching {
                    socket.outputStream.write(0)
                    socket.outputStream.flush()
                }
            } finally {
                receivedDescriptors.forEach { descriptor ->
                    runCatching { Os.close(descriptor) }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
    }
}
