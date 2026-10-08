package com.lanlink.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * LAN-Link v2 — a pure QR app.
 *
 * 1. Scan the fully encrypted QR (QL1 / AES-256-GCM — only this app can
 *    decode it) shown by the LAN-Link script or generated on
 *    https://c4sf4qh0-d.space-z.ai — or decode one from a picture.
 * 2. The payload carries the PC address + session code.
 * 3. The app then offers three actions: watch the screen, the front
 *    camera or the back camera — streaming straight to the PC over WiFi.
 *
 * No manual IP entry, no codes to type, nothing else to configure.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scanPanel: View
    private lateinit var controlPanel: View
    private lateinit var statusText: TextView
    private lateinit var targetText: TextView

    /** Feature waiting for a runtime permission before it can start. */
    private var pendingFeature: String? = null

    // -- connection target (filled by the encrypted QR, remembered locally) --
    private var host = ""
    private var port = ""
    private var token = ""

    // -- activity result launchers --------------------------------------

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val raw = result.data?.getStringExtra(ScannerActivity.EXTRA_URL).orEmpty()
                if (raw.isNotEmpty()) handleQrText(raw)
            }
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) decodeImage(uri)
        }

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                launchService("screen", result.resultCode, result.data)
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val feature = pendingFeature
            pendingFeature = null
            if (granted && feature != null) {
                launchService(feature)
            } else if (!granted) {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // -- lifecycle -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanPanel = findViewById(R.id.scanPanel)
        controlPanel = findViewById(R.id.controlPanel)
        statusText = findViewById(R.id.statusText)
        targetText = findViewById(R.id.targetText)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
        }
        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            imagePicker.launch("image/*")
        }
        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
        }
        findViewById<Button>(R.id.btnScreen).setOnClickListener { startFeature("screen") }
        findViewById<Button>(R.id.btnFront).setOnClickListener { startFeature("front") }
        findViewById<Button>(R.id.btnBack).setOnClickListener { startFeature("back") }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, StreamService::class.java))
        }

        loadTarget()
        if (hasTarget()) showControl() else showScan()
        requestNotificationPermissionIfNeeded()
        statusText.text = StreamStatus.text
    }

    override fun onResume() {
        super.onResume()
        StreamStatus.listener = { statusText.text = it }
        statusText.text = StreamStatus.text
    }

    override fun onPause() {
        StreamStatus.listener = null
        super.onPause()
    }

    // ------------------------------------------------------------------
    // QR handling — the heart of the app
    // ------------------------------------------------------------------

    /** Handle any scanned/decoded QR text. Only QL1 payloads are accepted. */
    private fun handleQrText(raw: String) {
        val json = Ql1.decrypt(raw.trim())
        if (json == null) {
            Toast.makeText(
                this,
                "Not a LAN-Link QR — it must be the encrypted code from the script or the website",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // QRs expire after 24h (set by the generator)
        val exp = json.optLong("exp", 0L)
        if (exp in 1..System.currentTimeMillis() / 1000) {
            Toast.makeText(this, "This QR has expired — generate a fresh one", Toast.LENGTH_LONG).show()
            return
        }

        val newHost = json.optString("host", "").trim()
        val newPort = json.optInt("port", 8080).toString()
        val newToken = json.optString("t", "").trim()
        if (newHost.isEmpty()) {
            Toast.makeText(this, "QR has no server address inside", Toast.LENGTH_LONG).show()
            return
        }

        host = newHost
        port = newPort
        if (newToken.isNotEmpty()) token = newToken
        saveTarget()
        showControl()
        Toast.makeText(this, "Target: $host:$port", Toast.LENGTH_SHORT).show()

        // A QR can carry a specific command (screen / front / back) —
        // it starts automatically. cmd "any" leaves the choice on screen.
        val cmd = json.optString("cmd", "any")
        if (cmd == "screen" || cmd == "front" || cmd == "back") {
            statusText.postDelayed({ startFeature(cmd) }, 450)
        }
    }

    /** Decode a QR from a picked image in the background, then route it. */
    private fun decodeImage(uri: Uri) {
        Thread {
            var text: String? = null
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        text = QrImages.decode(bmp)
                        bmp.recycle()
                    }
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
    // Panels + target persistence
    // ------------------------------------------------------------------

    private fun hasTarget() = host.isNotEmpty() && port.isNotEmpty()

    private fun showScan() {
        scanPanel.visibility = View.VISIBLE
        controlPanel.visibility = View.GONE
    }

    private fun showControl() {
        scanPanel.visibility = View.GONE
        controlPanel.visibility = View.VISIBLE
        targetText.text = "$host:$port"
    }

    private fun loadTarget() {
        val p = getSharedPreferences("lanlink", MODE_PRIVATE)
        host = p.getString("host", "").orEmpty()
        port = p.getString("port", "").orEmpty()
        token = p.getString("token", "").orEmpty()
    }

    private fun saveTarget() {
        getSharedPreferences("lanlink", MODE_PRIVATE).edit()
            .putString("host", host)
            .putString("port", port)
            .putString("token", token)
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
            if (feature == "screen" && resultCode != Int.MIN_VALUE) {
                putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
                putExtra(StreamService.EXTRA_RESULT_DATA, data)
            }
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Connecting to $host:$port ...", Toast.LENGTH_SHORT).show()
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
