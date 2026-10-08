package com.lanlink.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Main screen: connection fields (prefilled from the bundled defaults or
 * the QR / deep link), the three feature buttons and stop.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var editToken: EditText
    private lateinit var statusText: TextView

    /** Feature waiting for a runtime permission before it can start. */
    private var pendingFeature: String? = null

    // -- activity result launchers --------------------------------------

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val url = result.data?.getStringExtra(ScannerActivity.EXTRA_URL).orEmpty()
                if (url.isNotEmpty()) {
                    applyConnection(
                        host = Uri.parse(url).host ?: editHost.text.toString(),
                        port = (Uri.parse(url).port.takeIf { it > 0 } ?: 8080).toString(),
                        token = Uri.parse(url).getQueryParameter("t").orEmpty()
                    )
                    startFeature(Uri.parse(url).getQueryParameter("f") ?: "screen")
                }
            }
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

        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        editToken = findViewById(R.id.editToken)
        statusText = findViewById(R.id.statusText)

        applyBundledDefaults()
        requestNotificationPermissionIfNeeded()

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
        }
        findViewById<Button>(R.id.btnScreen).setOnClickListener { startFeature("screen") }
        findViewById<Button>(R.id.btnFront).setOnClickListener { startFeature("front") }
        findViewById<Button>(R.id.btnBack).setOnClickListener { startFeature("back") }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, StreamService::class.java))
        }

        handleIntent(intent)
        statusText.text = StreamStatus.text
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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
    // Connection inputs
    // ------------------------------------------------------------------

    /** Defaults bundled by "python lanlink.py" option 1. */
    private fun applyBundledDefaults() {
        try {
            assets.open("lanlink.properties").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val key = line.substringBefore("=").trim()
                    val value = line.substringAfter("=", "").trim()
                    when (key) {
                        "host" -> if (editHost.text.isEmpty()) editHost.setText(value)
                        "port" -> if (editPort.text.isEmpty()) editPort.setText(value)
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (editPort.text.isEmpty()) editPort.setText("8080")
    }

    private fun applyConnection(host: String, port: String, token: String) {
        if (host.isNotEmpty()) editHost.setText(host)
        if (port.isNotEmpty()) editPort.setText(port)
        if (token.isNotEmpty()) editToken.setText(token.trim().uppercase())
    }

    /**
     * Handles the lanlink://connect?host=..&port=..&token=..&feature=..
     * deep link produced when the QR is scanned with Google Lens.
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "lanlink") return
        applyConnection(
            host = data.getQueryParameter("host").orEmpty(),
            port = data.getQueryParameter("port").orEmpty(),
            token = data.getQueryParameter("token").orEmpty()
        )
        val feature = data.getQueryParameter("feature")
        if (!feature.isNullOrEmpty() && feature != "none") {
            // Give the UI a beat to paint, then auto-start the requested feature
            statusText.postDelayed({ startFeature(feature) }, 350)
        }
    }

    // ------------------------------------------------------------------
    // Feature start flows
    // ------------------------------------------------------------------

    private fun startFeature(feature: String) {
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().trim()
        val token = editToken.text.toString().trim()
        if (host.isEmpty() || port.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "Fill IP, port and session code (or scan the QR)", Toast.LENGTH_LONG).show()
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
            putExtra(StreamService.EXTRA_HOST, editHost.text.toString().trim())
            putExtra(StreamService.EXTRA_PORT, editPort.text.toString().trim().toIntOrNull() ?: 8080)
            putExtra(StreamService.EXTRA_TOKEN, editToken.text.toString().trim())
            putExtra(StreamService.EXTRA_FEATURE, feature)
            if (feature == "screen" && resultCode != Int.MIN_VALUE) {
                putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
                putExtra(StreamService.EXTRA_RESULT_DATA, data)
            }
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Connecting to the PC...", Toast.LENGTH_SHORT).show()
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
