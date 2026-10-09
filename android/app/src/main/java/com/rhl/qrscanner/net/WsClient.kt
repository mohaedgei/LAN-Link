package com.rhl.qrscanner.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Minimal OkHttp WebSocket wrapper used by the streaming service.
 *
 * The app is the *client*: it connects to the PC server URL embedded in
 * the QR code (or bundled defaults) and pushes JPEG frames to it, while
 * receiving JSON control commands (tap / swipe / pinch / key / text).
 */
class WsClient(
    private val url: String,
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) {

    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        ws = SHARED_CLIENT.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(t.message ?: "connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(reason.ifEmpty { "closed ($code)" })
            }
        })
    }

    /** Send a JPEG frame as a binary WebSocket message. */
    fun sendFrame(jpeg: ByteArray): Boolean {
        return ws?.send(jpeg.toByteString(0, jpeg.size)) ?: false
    }

    /** Send a JSON text message (status updates to the PC). */
    fun sendText(text: String): Boolean {
        return ws?.send(text) ?: false
    }

    fun close() {
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        ws = null
    }

    companion object {
        /**
         * One process-wide client: every reconnect reuses the same
         * connection pool and dispatcher instead of leaking threads.
         */
        private val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
