package com.mdblisthub.desktop

import com.mdblisthub.tv.BuildConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Google sign-in for a desktop window.
 *
 * The TV app asks Android's Credential Manager for an ID token; a desktop has
 * no such picker, and an embedded web view has no Google session to offer.
 * So the picker runs in the user's own browser — already signed in to Google —
 * from a page served on a fixed loopback origin (registered as an Authorized
 * JavaScript origin on the OAuth client, see README), and the credential comes
 * back to this server with a POST.
 */
object GoogleSignInLoopback {

    class Cancelled : Exception("Google sign-in was cancelled or timed out")

    const val PORT = 47311
    private const val TIMEOUT_MS = 5 * 60_000L

    private var server: HttpServer? = null
    private var pending: CompletableDeferred<String>? = null

    suspend fun requestIdToken(): String {
        pending?.cancel()
        val deferred = CompletableDeferred<String>()
        pending = deferred
        withContext(Dispatchers.IO) { ensureServer() }
        if (!DesktopShell.open("http://127.0.0.1:$PORT/google-signin.html")) {
            throw IllegalStateException("Could not open the system browser")
        }
        return try {
            withTimeout(TIMEOUT_MS) { deferred.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw Cancelled()
        } finally {
            if (pending === deferred) pending = null
        }
    }

    @Synchronized
    private fun ensureServer() {
        if (server != null) return
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0)
        http.createContext("/google-signin.html") { exchange -> servePage(exchange) }
        http.createContext("/google-callback") { exchange -> receive(exchange) }
        http.createContext("/") { exchange -> respond(exchange, 404, "text/plain", "Not found") }
        http.executor = null
        http.start()
        server = http
    }

    private fun servePage(exchange: HttpExchange) {
        val page = GoogleSignInLoopback::class.java.getResourceAsStream("/google-signin.html")
            ?.use { it.readBytes().decodeToString() }
            .orEmpty()
            .replace(DEFAULT_CLIENT_ID, BuildConfig.GOOGLE_WEB_CLIENT_ID.ifBlank { DEFAULT_CLIENT_ID })
        respond(exchange, 200, "text/html; charset=utf-8", page)
    }

    private fun receive(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respond(exchange, 405, "text/plain", "Method not allowed")
        val credential = runCatching {
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            Json.parseToJsonElement(body).jsonObject["credential"]?.jsonPrimitive?.content
        }.getOrNull()
        if (credential.isNullOrBlank()) return respond(exchange, 400, "text/plain", "Bad request")
        pending?.complete(credential)
        respond(exchange, 200, "application/json", """{"ok":true}""")
        DesktopWindow.bringToFront()
    }

    private fun respond(exchange: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private const val DEFAULT_CLIENT_ID =
        "862741916290-5bhenqt1prf98341g2douaedchkqv3nb.apps.googleusercontent.com"
}
