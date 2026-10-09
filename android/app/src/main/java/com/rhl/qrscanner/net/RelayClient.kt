package com.rhl.qrscanner.net

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Relay transport (v2.1) — used when the QR says via="relay", i.e. the PC
 * advertises a PUBLIC IP and streams must pass through the website
 * (https://c4sf4qh0-d.space-z.ai/api/relay/frame).
 *
 * Frames are POSTed as raw JPEG bodies with the session token in a header.
 * A tiny bounded queue keeps memory flat: when the network is slower than
 * the capture, the OLDEST frames are dropped — live beats complete.
 */
class RelayClient(
    private val siteBase: String,
    private val token: String,
    private val featureProvider: () -> String,
    private val onOpened: () -> Unit,
    private val onClosed: (String) -> Unit,
) {

    companion object {
        private const val TAG = "RelayClient"
        private const val QUEUE_CAPACITY = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    /** "Connected" for the relay means: the first POST was accepted. */
    @Volatile
    var firstFrameAccepted: Boolean = false
        private set

    fun connect() {
        if (!running.compareAndSet(false, true)) return
        firstFrameAccepted = false
        // the relay is connectionless — report open immediately; the worker
        // switches the status to "streaming" on the first accepted frame
        onOpened()
        worker = Thread {
            try {
                drain()
            } catch (_: InterruptedException) {
            }
        }.also { it.start() }
    }

    private fun drain() {
        while (running.get()) {
            val frame = try {
                queue.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            val ok = post(frame)
            if (ok && !firstFrameAccepted) {
                firstFrameAccepted = true
            }
            if (!ok) {
                onClosed("relay rejected a frame")
                // brief backoff so a dead relay does not spin the network
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun post(jpeg: ByteArray): Boolean {
        return try {
            val body = jpeg.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$siteBase/api/relay/frame")
                .header("X-Lanlink-Token", token)
                .header("X-Lanlink-Feature", featureProvider())
                .header("X-Lanlink-Device", android.os.Build.MODEL ?: "android")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame post failed: ${e.message}")
            false
        }
    }

    /** Offer a JPEG frame; when the queue is full the oldest is dropped. */
    fun sendFrame(jpeg: ByteArray): Boolean {
        if (!running.get()) return false
        if (!queue.offer(jpeg)) {
            queue.poll()          // drop the oldest
            return queue.offer(jpeg)
        }
        return true
    }

    fun sendText(text: String): Boolean = true // no back-channel in relay mode

    fun close() {
        running.set(false)
        try { worker?.interrupt() } catch (_: Exception) {}
        worker = null
        queue.clear()
    }
}
