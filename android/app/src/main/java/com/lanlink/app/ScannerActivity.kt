package com.lanlink.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.lanlink.app.capture.Yuv
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Built-in QR scanner: point the phone at the QR shown by the PC script,
 * the app extracts the server URL and returns it to MainActivity.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val THROTTLE_MS = 220L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastDecode = 0L
    private var done = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, "Camera permission is required to scan", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(worker, this::analyze)
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (done || now - lastDecode < THROTTLE_MS) {
            image.close()
            return
        }
        lastDecode = now

        val rotation = image.imageInfo.rotationDegrees
        val width = image.width
        val height = image.height
        val nv21 = Yuv.toNv21(image)
        image.close()

        val url = try {
            val bitmap = rotatedBitmap(nv21, width, height, rotation)
            decodeQr(bitmap)
        } catch (_: Exception) {
            null
        }

        if (url != null && !done) {
            done = true
            setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_URL, url))
            runOnUiThread { finish() }
        }
    }

    private fun rotatedBitmap(nv21: ByteArray, width: Int, height: Int, rotation: Int): Bitmap {
        val yuv = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21, width, height, null
        )
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
        val bytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    /** Decode a QR from the bitmap; returns the raw text or null. */
    private fun decodeQr(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return try {
            QRCodeReader().decode(binary).text
        } catch (_: NotFoundException) {
            null
        }
    }

    override fun onDestroy() {
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
        }
        worker.shutdownNow()
        super.onDestroy()
    }
}
