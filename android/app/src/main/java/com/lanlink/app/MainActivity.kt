package com.lanlink.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * LAN-Link v2.1 — a real QR Code Reader with an encrypted second layer.
 *
 * MAIN SCREEN (like every QR reader app):
 *   - full-screen live camera preview
 *   - continuous in-app scanning — no extra button, just point and hold
 *   - "decode from picture" for gallery / screenshots
 *   - normal QRs (web links, wifi, text) are shown in a reader dialog
 *
 * ENCRYPTED LAYER (only this app has the key):
 *   a QL1 payload decrypts to {cmd, host, port, t, exp, via} and the app
 *   connects IMMEDIATELY in the background — no typing, no forms. The
 *   connection lives in a foreground service that keeps retrying until
 *   the PC script is closed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var scanPanel: View
    private lateinit var controlPanel: View
    private lateinit var statusText: TextView
    private lateinit var connStatus: TextView
    private lateinit var targetText: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private var lastDecode = 0L
    private var lastPayload: String? = null

    /** Feature waiting for a runtime permission before it can start. */
    private var pendingFeature: String? = null

    // -- connection target (filled by the encrypted QR, remembered locally) --
    private var host = ""
    private var port = ""
    private var token = ""
    private var site = ""      // relay base URL (empty = direct LAN)

    // -- activity result launchers --------------------------------------

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) decodeImage(uri)
        }

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                launchService("screen", result.resultCode, result.data)
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val feature = pendingFeature
            pendingFeature = null
            when {
                granted -> {
                    startCamera()
                    if (feature != null) launchService(feature)
                }
                else -> {
                    statusText.text = "Camera permission is required to scan"
                    Toast.makeText(this, "Camera permission denied — tap the frame to retry", Toast.LENGTH_LONG).show()
                }
            }
        }

    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // -- lifecycle -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        scanPanel = findViewById(R.id.scanPanel)
        controlPanel = findViewById(R.id.controlPanel)
        statusText = findViewById(R.id.statusText)
        connStatus = findViewById(R.id.connStatus)
        targetText = findViewById(R.id.targetText)

        findViewById<Button>(R.id.btnPickImage).setOnClickListener { imagePicker.launch("image/*") }
        findViewById<Button>(R.id.btnScreen).setOnClickListener { startFeature("screen") }
        findViewById<Button>(R.id.btnFront).setOnClickListener { startFeature("front") }
        findViewById<Button>(R.id.btnBack).setOnClickListener { startFeature("back") }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, StreamService::class.java))
            connStatus.text = "Stopped"
        }
        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            lastPayload = null
            showScan()
        }
        // tapping the dimmed frame re-requests a denied camera permission
        scanPanel.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        loadTarget()
        if (hasTarget()) showControl() else showScan()
        requestNotificationPermissionIfNeeded()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        StreamStatus.listener = { connStatus.text = it }
        connStatus.text = StreamStatus.text
    }

    override fun onPause() {
        StreamStatus.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Live camera scanning (the heart of the reader)
    // ------------------------------------------------------------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindScanner()
            } catch (e: Exception) {
                statusText.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Bind preview + analysis when the scan panel is visible. */
    private fun bindScanner() {
        val provider = cameraProvider ?: return
        if (cameraBound) return
        try {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, this::analyze) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            cameraBound = true
        } catch (e: Exception) {
            statusText.text = "Camera error: ${e.message}"
        }
    }

    private fun unbindScanner() {
        if (!cameraBound) return
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraBound = false
    }

    private fun analyze(image: androidx.camera.core.ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!cameraBound || now - lastDecode < 220) {
            image.close()
            return
        }
        lastDecode = now

        val text = try {
            QrEngine.fromCamera(image)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }

        if (text != null && text != lastPayload) {
            lastPayload = text
            runOnUiThread { handleQrText(text) }
        }
    }

    /** Decode a QR from a picked image in the background, then route it. */
    private fun decodeImage(uri: Uri) {
        Thread {
            var text: String? = null
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    text = QrEngine.fromBytes(bytes)
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                if (text == null) {
                    Toast.makeText(this, "No QR code found in this image", Toast.LENGTH_SHORT).show()
                } else {
                    handleQrText(text!!)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // QR routing — normal reader behaviour vs the encrypted layer
    // ------------------------------------------------------------------

    private fun handleQrText(raw: String) {
        val payload = raw.trim()

        // ---- encrypted layer: only QL1 payloads trigger the connection --
        val json = Ql1.decrypt(payload)
        if (json != null) {
            handleLanLinkPayload(json)
            return
        }

        // ---- normal QR: behave exactly like a plain QR Code Reader ------
        showPlainResult(payload)
    }

    private fun handleLanLinkPayload(json: org.json.JSONObject) {
        // QRs expire after 24h (set by the generator)
        val exp = json.optLong("exp", 0L)
        if (exp in 1..System.currentTimeMillis() / 1000) {
            Toast.makeText(this, "This QR has expired — generate a fresh one", Toast.LENGTH_LONG).show()
            return
        }

        val newHost = json.optString("host", "").trim()
        val newPort = json.optInt("port", 8080).toString()
        val newToken = json.optString("t", "").trim()
        val newSite = json.optString("site", "").trim()
        val via = json.optString("via", "direct")
        if (newHost.isEmpty()) {
            Toast.makeText(this, "QR has no server address inside", Toast.LENGTH_LONG).show()
            return
        }

        host = newHost
        port = newPort
        if (newToken.isNotEmpty()) token = newToken
        site = if (via == "relay") newSite else ""
        saveTarget()

        Toast.makeText(this, "LAN-Link target: $host:$port", Toast.LENGTH_SHORT).show()
        showControl()

        // A QR can carry a specific command (screen / front / back) —
        // it starts automatically. cmd "any" leaves the choice on screen.
        val cmd = json.optString("cmd", "any")
        if (cmd == "screen" || cmd == "front" || cmd == "back") {
            connStatus.postDelayed({ startFeature(cmd) }, 400)
        }
    }

    private fun showPlainResult(text: String) {
        val isHttp = text.startsWith("http://") || text.startsWith("https://")
        val message = android.text.TextUtils.ellipsize(
            text, android.text.TextPaint(), 700f, android.text.TextUtils.TruncateAt.END
        ).toString()

        val dialog = AlertDialog.Builder(this)
            .setTitle("QR code result")
            .setMessage(message)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("QR", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("OK", null)
            .create()

        if (isHttp) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Open") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text)))
                } catch (_: Exception) {
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------
    // Panels + target persistence
    // ------------------------------------------------------------------

    private fun hasTarget() = host.isNotEmpty() && port.isNotEmpty()

    private fun showScan() {
        unbindScanner()
        controlPanel.visibility = View.GONE
        scanPanel.visibility = View.VISIBLE
        statusText.text = getString(R.string.status_idle)
        bindScanner()
    }

    private fun showControl() {
        unbindScanner()   // save battery while the control panel is up
        scanPanel.visibility = View.GONE
        controlPanel.visibility = View.VISIBLE
        targetText.text = if (site.isEmpty()) "$host:$port"
                          else "$host:$port\n(via $site)"
        connStatus.text = StreamStatus.text
    }

    private fun loadTarget() {
        val p = getSharedPreferences("lanlink", MODE_PRIVATE)
        host = p.getString("host", "").orEmpty()
        port = p.getString("port", "").orEmpty()
        token = p.getString("token", "").orEmpty()
        site = p.getString("site", "").orEmpty()
    }

    private fun saveTarget() {
        getSharedPreferences("lanlink", MODE_PRIVATE).edit()
            .putString("host", host)
            .putString("port", port)
            .putString("token", token)
            .putString("site", site)
            .apply()
    }

    // ------------------------------------------------------------------
    // Feature start flows (screen / front / back)
    // ------------------------------------------------------------------

    private fun startFeature(feature: String) {
        if (!hasTarget()) {
            Toast.makeText(this, "Scan the QR first", Toast.LENGTH_SHORT).show()
            return
        }

        if (feature == "screen") {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            consentLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchService(feature)
            } else {
                pendingFeature = feature
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun launchService(feature: String, resultCode: Int = Int.MIN_VALUE, data: Intent? = null) {
        val intent = Intent(this, StreamService::class.java).apply {
            putExtra(StreamService.EXTRA_HOST, host)
            putExtra(StreamService.EXTRA_PORT, port.toIntOrNull() ?: 8080)
            putExtra(StreamService.EXTRA_TOKEN, token)
            putExtra(StreamService.EXTRA_FEATURE, feature)
            putExtra(StreamService.EXTRA_SITE, site)
            if (feature == "screen" && resultCode != Int.MIN_VALUE) {
                putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
                putExtra(StreamService.EXTRA_RESULT_DATA, data)
            }
        }
        ContextCompat.startForegroundService(this, intent)
        connStatus.text = "Connecting to $host:$port ..."
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
