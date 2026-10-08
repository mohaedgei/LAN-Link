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
 *  1. Connects to the PC server as a WebSocket client (role=stream).
 *  2. Starts the requested capture engine (screen / front / back).
 *  3. Pushes JPEG frames to the server.
 *  4. Executes control JSON coming back (tap / swipe / pinch / key / text)
 *     through the Accessibility service and the IME.
 */
class StreamService : LifecycleService() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_FEATURE = "feature"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.lanlink.app.STOP"
        private const val CHANNEL_ID = "lanlink_stream"
        private const val NOTIFICATION_ID = 42
    }

    private var ws: WsClient? = null
    private var screen: ScreenCapture? = null
    private var camera: CameraCapture? = null
    private var projection: MediaProjection? = null
    private var feature: String = "screen"
    private var host: String = ""
    private var port: Int = 0
    private var token: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        feature = intent?.getStringExtra(EXTRA_FEATURE) ?: "screen"

        if (host.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground(feature)

        // Screen share: store the projection consent that MainActivity passed
        // (must happen after startForeground on Android 14+).
        if (feature == "screen" && intent?.hasExtra(EXTRA_RESULT_CODE) == true) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            obtainProjection(resultCode, resultData)
        }

        StreamStatus.set("Connecting to $host:$port ...")
        connect()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopCapture()
        ws?.close()
        ws = null
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
    // WebSocket connection
    // ------------------------------------------------------------------

    private fun connect() {
        val url = "ws://$host:$port/ws?t=$token&role=stream&feature=$feature"
        ws = WsClient(
            url = url,
            onOpen = {
                StreamStatus.set("Connected — starting $feature")
                startCapture(feature)
            },
            onText = { raw ->
                try {
                    val json = JSONObject(raw)
                    handleControl(json)
                } catch (_: JSONException) {
                }
            },
            onClosed = { reason ->
                stopCapture()
                StreamStatus.set("Disconnected: $reason")
            }
        )
        ws?.connect()
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
                screen = ScreenCapture(this, p) { frame -> ws?.sendFrame(frame) }
                    .also { it.start() }
            }
            "front" -> startCamera(front = true)
            "back" -> startCamera(front = false)
        }
        this.feature = featureName
        sendStatus("capture-started")
        StreamStatus.set("Streaming $featureName")
    }

    private fun startCamera(front: Boolean) {
        camera?.stop()
        camera = CameraCapture(this, front) { frame -> ws?.sendFrame(frame) }
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

    /** Switch features live (requested from the PC viewer buttons). */
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
    // Control commands from the PC viewer
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

    /**
     * Store the projection obtained from the consent dialog result.
     * Called from onStartCommand with the extras MainActivity provided.
     */
    private fun obtainProjection(resultCode: Int, resultData: Intent?) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = try {
            manager.getMediaProjection(resultCode, resultData ?: Intent())
        } catch (_: Exception) {
            null
        }
    }
}
