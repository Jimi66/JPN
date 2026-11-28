package com.jpn.app.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
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

    // Each TURN session gets its own abstract socket name. This prevents a
    // replacement service instance from colliding with a stale listener.
    internal val socketName = "jpn.turn.protect.${Process.myPid()}.${System.nanoTime()}"

    private val closed = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "TURN socket protector is closed" }
        if (server != null) return

        val localServer = LocalServerSocket(socketName)
        server = localServer
        TurnDiagnostics.record(vpnService, "Локальный сокет защиты TURN создан")

        acceptThread = Thread({
            while (!closed.get()) {
                val client = try {
                    localServer.accept()
                } catch (error: IOException) {
                    if (!closed.get()) {
                        Log.e("JPN-TURN", "Socket-protection listener stopped unexpectedly", error)
                        TurnDiagnostics.record(vpnService, "Ошибка listener защиты сокетов: ${error.javaClass.simpleName}")
                        close()
                    }
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
                    TurnDiagnostics.record(vpnService, "Отклонён запрос защиты сокета: UID не совпадает")
                    socket.outputStream.write(0)
                    socket.outputStream.flush()
                    return
                }

                if (socket.inputStream.read() < 0) return
                receivedDescriptors = socket.ancillaryFileDescriptors ?: emptyArray()
                val received = receivedDescriptors.firstOrNull()
                if (received == null) {
                    TurnDiagnostics.record(vpnService, "Go-клиент запросил защиту, но FD не передан")
                    socket.outputStream.write(0)
                    socket.outputStream.flush()
                    return
                }

                val protected = ParcelFileDescriptor.dup(received).use { duplicate ->
                    vpnService.protect(duplicate.fd)
                }

                TurnDiagnostics.record(vpnService, if (protected) "Защита исходящего TURN-сокета: успешно" else "Защита исходящего TURN-сокета: protect() вернул false")
                socket.outputStream.write(if (protected) 1 else 0)
                socket.outputStream.flush()
            } catch (error: Exception) {
                TurnDiagnostics.record(vpnService, "Ошибка обработки защиты сокета: ${error.javaClass.simpleName}")
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
        val thread = acceptThread
        acceptThread = null
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(1_000) }
            if (thread.isAlive) {
                TurnDiagnostics.record(vpnService, "Поток listener защиты TURN не завершился за 1 секунду")
            }
        }
        TurnDiagnostics.record(vpnService, "Локальный сокет защиты TURN освобождён")
    }
}
