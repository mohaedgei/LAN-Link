package com.lanlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.lanlink.app.capture.CameraCapture
import com.lanlink.app.capture.ScreenCapture
import com.lanlink.app.control.ControlAccessibilityService
import com.lanlink.app.net.RelayClient
import com.lanlink.app.net.WsClient
import org.json.JSONException
import org.json.JSONObject

/** Tiny status bus between the service and MainActivity. */
object StreamStatus {
    @Volatile
    var text: String = "Idle"
        private set

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun set(value: String) {
        text = value
        main.post { listener?.invoke(value) }
    }
}

/**
 * Foreground service that:
 *  1. Opens the transport chosen by the QR payload:
 *       - direct (LAN):  WebSocket  ws://host:port/ws?...&role=stream
 *       - relay (public IP): HTTPS POST frames through the website
 *  2. Starts the requested capture engine (screen / front / back).
 *  3. Pushes JPEG frames to the PC.
 *  4. AUTO-RECONNECTS forever while the service is alive — if the PC
 *     script restarts, the app quietly joins again. The user stops it
 *     explicitly (Stop button / notification), or closing the app.
 */
class StreamService : LifecycleService() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_FEATURE = "feature"
        const val EXTRA_SITE = "site"      // relay base URL ("" = direct LAN)
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.lanlink.app.STOP"
        private const val CHANNEL_ID = "lanlink_stream"
        private const val NOTIFICATION_ID = 42
        private const val RECONNECT_MS = 3000L
    }

    private var ws: WsClient? = null
    private var relay: RelayClient? = null
    private var screen: ScreenCapture? = null
    private var camera: CameraCapture? = null
    private var projection: MediaProjection? = null
    private var feature: String = "screen"
    private var host: String = ""
    private var port: Int = 0
    private var token: String = ""
    private var site: String = ""          // empty = direct LAN connection

    private val main = Handler(Looper.getMainLooper())
    private var retries = 0
    @Volatile
    private var userStopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            userStopped = true
            stopSelf()
            return START_NOT_STICKY
        }

        val newHost = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        if (newHost.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // a re-launch (feature switch) re-initialises everything cleanly
        teardownTransport()
        stopCapture()

        userStopped = false
        retries = 0
        host = newHost
        port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        feature = intent?.getStringExtra(EXTRA_FEATURE) ?: "screen"
        site = intent?.getStringExtra(EXTRA_SITE).orEmpty().trim().trimEnd('/')

        startInForeground(feature)

        // Screen share: store the projection consent that MainActivity passed
        // (must happen after startForeground on Android 14+).
        if (feature == "screen" && intent?.hasExtra(EXTRA_RESULT_CODE) == true) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            obtainProjection(resultCode, resultData)
        }

        StreamStatus.set(if (site.isEmpty()) "Connecting to $host:$port ..." else "Connecting via website relay ...")
        connect()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        userStopped = true
        main.removeCallbacksAndMessages(null)
        stopCapture()
        teardownTransport()
        releaseProjection()
        StreamStatus.set("Stopped")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun startInForeground(featureName: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lanlink)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("${getString(R.string.notif_text)} — $featureName")
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()

        val type = if (featureName == "screen")
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------
    // Transport + capture plumbing
    // ------------------------------------------------------------------

    private fun connect() {
        if (userStopped) return
        if (site.isEmpty()) {
            // --- direct LAN: WebSocket to the PC server -------------------
            val url = "ws://$host:$port/ws?t=$token&role=stream&feature=$feature"
            ws = WsClient(
                url = url,
                onOpen = {
                    retries = 0
                    StreamStatus.set("Connected — starting $feature")
                    startCapture(feature)
                },
                onText = { raw ->
                    try {
                        handleControl(JSONObject(raw))
                    } catch (_: JSONException) {
                    }
                },
                onClosed = { reason -> onTransportDown("Disconnected: $reason") }
            )
            ws?.connect()
        } else {
            // --- relay: frames go through the website ---------------------
            relay = RelayClient(
                siteBase = site,
                token = token,
                featureProvider = { feature },
                onOpened = {
                    retries = 0
                    StreamStatus.set("Relay connected — starting $feature")
                    startCapture(feature)
                },
                onClosed = { reason -> onTransportDown(reason) }
            )
            relay?.connect()
        }
    }

    /** Never give up while the service lives — the PC may just be restarting. */
    private fun onTransportDown(reason: String) {
        stopCapture()
        if (userStopped) {
            StreamStatus.set(reason)
            return
        }
        retries += 1
        StreamStatus.set("$reason — reconnecting (#$retries) in ${RECONNECT_MS / 1000}s")
        main.postDelayed({ connect() }, RECONNECT_MS)
    }

    private fun teardownTransport() {
        try { ws?.close() } catch (_: Exception) {}
        ws = null
        try { relay?.close() } catch (_: Exception) {}
        relay = null
    }

    private fun sendStatus(value: String) {
        try {
            ws?.sendText(JSONObject().put("type", "status").put("value", value).toString())
        } catch (_: JSONException) {
        }
    }

    // ------------------------------------------------------------------
    // Capture engines
    // ------------------------------------------------------------------

    private fun startCapture(featureName: String) {
        when (featureName) {
            "screen" -> {
                val p = projection
                if (p == null) {
                    sendStatus("screen-consent-needed")
                    StreamStatus.set("Screen consent missing — restart screen share from the app")
                    return
                }
                screen = ScreenCapture(this, p) { frame -> pushFrame(frame) }
                    .also { it.start() }
            }
            "front" -> startCamera(front = true)
            "back" -> startCamera(front = false)
            else -> {
                // "any": the QR opened the connection but picked no feature —
                // the PC viewer chooses it (Screen / Front / Back buttons).
                this.feature = featureName
                sendStatus("waiting-feature")
                StreamStatus.set("Connected — pick the feature on the PC viewer")
                return
            }
        }
        this.feature = featureName
        sendStatus("capture-started")
        if (featureName == "screen") {
            // tell the viewer whether remote control is armed on this phone
            sendStatus(
                if (ControlAccessibilityService.isReady()) "control-ready" else "control-off"
            )
        }
        StreamStatus.set("Streaming $featureName")
    }

    private fun pushFrame(jpeg: ByteArray) {
        ws?.sendFrame(jpeg)
        relay?.sendFrame(jpeg)
    }

    private fun startCamera(front: Boolean) {
        camera?.stop()
        camera = CameraCapture(this, front) { frame -> pushFrame(frame) }
            .also { it.start() }
    }

    private fun stopCapture() {
        try { screen?.stop() } catch (_: Exception) {}
        try { camera?.stop() } catch (_: Exception) {}
        screen = null
        camera = null
    }

    private fun releaseProjection() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    /** Switch features live (requested from the PC viewer). */
    private fun switchFeature(newFeature: String) {
        if (newFeature == "stop") {
            stopCapture()
            StreamStatus.set("Idle (stream stopped from PC)")
            sendStatus("capture-stopped")
            return
        }
        if (newFeature == "screen" && projection == null) {
            sendStatus("screen-consent-needed")
            return
        }
        stopCapture()
        startCapture(newFeature)
    }

    // ------------------------------------------------------------------
    // Control commands from the PC viewer (direct mode only)
    // ------------------------------------------------------------------

    private fun handleControl(json: JSONObject) {
        when (json.optString("type")) {
            "feature" -> switchFeature(json.optString("value", "screen"))
            "tap", "swipe", "pinch", "key", "text" -> ControlAccessibilityService.handle(json)
        }
    }

    // ------------------------------------------------------------------
    // MediaProjection plumbing (screen share)
    // ------------------------------------------------------------------

    private fun obtainProjection(resultCode: Int, resultData: Intent?) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = try {
            manager.getMediaProjection(resultCode, resultData ?: Intent())
        } catch (_: Exception) {
            null
        }
    }
}
