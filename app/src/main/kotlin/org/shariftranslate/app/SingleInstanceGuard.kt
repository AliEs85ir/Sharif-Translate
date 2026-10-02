package org.shariftranslate.app

import java.io.PrintWriter
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.io.IOException

object SingleInstanceGuard {

    private val port = System.getProperty("shariftranslate.instancePort")?.toIntOrNull()
        ?.takeIf { it in 1024..65535 } ?: 49186
    private const val FOCUS_SIGNAL = "FOCUS"
    private var serverSocket: ServerSocket? = null


    fun tryLock(onFocusRequested: () -> Unit): Boolean {
        return try {
            val listener = ServerSocket(port, 1, InetAddress.getLoopbackAddress())
            serverSocket = listener
            Thread {
                while (!listener.isClosed) {
                    try {
                        listener.accept().use { client ->
                            client.soTimeout = 2000
                            val signal = client.getInputStream().bufferedReader().readLine()
                            if (signal == FOCUS_SIGNAL) onFocusRequested()
                        }
                    } catch (_: SocketTimeoutException) {
                        // An incomplete focus signal must not block subsequent launches.
                    } catch (_: IOException) {
                        if (listener.isClosed) break
                    }
                }
            }.apply { isDaemon = true }.start()
            true
        } catch (_: BindException) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000)
                    PrintWriter(socket.getOutputStream(), true).println(FOCUS_SIGNAL)
                }
            }
            false
        }
    }

    fun release() {
        serverSocket?.close()
    }
}
