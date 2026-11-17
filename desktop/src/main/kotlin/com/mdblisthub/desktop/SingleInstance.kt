package com.mdblisthub.desktop

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * One OmniStream at a time. A second launch (a double-clicked desktop icon,
 * say) asks the first to come forward and exits, instead of opening a second
 * window that would fight over the same database and player.
 */
object SingleInstance {
    private const val BASE_PORT = 47312
    private var server: ServerSocket? = null

    /** Returns Unit when this process is the instance, null when another one was signalled. */
    fun acquireOrSignal(onActivate: () -> Unit): Unit? {
        val port = runCatching { File(AppDirs.data, "instance.port").readText().trim().toInt() }.getOrDefault(BASE_PORT)
        val loopback = InetAddress.getLoopbackAddress()
        // Someone listening? Then they are us.
        runCatching {
            Socket(loopback, port).use { socket ->
                socket.soTimeout = 1500
                socket.getOutputStream().write("activate\n".toByteArray())
                val reply = socket.getInputStream().bufferedReader().readLine()
                if (reply == "omnistream") return null
            }
        }
        val listener = runCatching { ServerSocket(port, 4, loopback) }.getOrNull()
            ?: runCatching { ServerSocket(0, 4, loopback) }.getOrNull()
            ?: return Unit
        File(AppDirs.data, "instance.port").writeText(listener.localPort.toString())
        server = listener
        Thread({
            while (!listener.isClosed) {
                runCatching {
                    listener.accept().use { client ->
                        client.soTimeout = 1500
                        val line = client.getInputStream().bufferedReader().readLine()
                        client.getOutputStream().write("omnistream\n".toByteArray())
                        if (line == "activate") onActivate()
                    }
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
        return Unit
    }
}
