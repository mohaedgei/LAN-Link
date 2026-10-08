package com.lanlink.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Screen capture engine built on MediaProjection + ImageReader.
 *
 * Captures the mirrored display as RGBA frames, downscales to a target
 * of 1280x720 (720p) while keeping the aspect ratio, encodes each frame
 * as JPEG and pushes it to [onFrame] at ~10-11 fps.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val onFrame: (ByteArray) -> Unit,
) : MediaProjection.Callback() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val intervalMs = 90L

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: android.media.ImageReader? = null
    private var lastSent = 0L

    fun start() {
        val metrics = currentMetrics()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // Scale to fit 720p preserving aspect ratio (both orientations)
        val maxDim = maxOf(screenW, screenH).toFloat()
        val minDim = minOf(screenW, screenH).toFloat()
        val scale = minOf(TARGET_WIDTH / maxDim, TARGET_HEIGHT / minDim, 1f)
        val outW = (screenW * scale).toInt() / 2 * 2
        val outH = (screenH * scale).toInt() / 2 * 2

        val reader = android.media.ImageReader.newInstance(outW, outH, android.graphics.PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            worker.execute {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSent >= intervalMs) {
                        lastSent = now
                        onFrame(imageToJpeg(image))
                    }
                } catch (_: Exception) {
                } finally {
                    try { image.close() } catch (_: Exception) {}
                }
            }
        }, mainHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "lanlink-screen",
            outW, outH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, mainHandler
        )
        imageReader = reader
        projection.registerCallback(this, mainHandler)
    }

    private fun currentMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    /** User pressed "Stop casting" in the system UI. */
    override fun onStop() {
        // StreamService observes this through projection callbacks it owns.
    }

    fun stop() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection.unregisterCallback(this) } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
    }

    private fun imageToJpeg(image: Image): ByteArray {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (rowStride == width * 4) {
            val buffer = plane.buffer
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // Handle stride padding: copy row by row into a packed buffer
            val rowBytes = width * 4
            val packed: ByteBuffer = ByteBuffer.allocate(rowBytes * height)
            val buffer = plane.buffer
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.limit(row * rowStride + rowBytes)
                packed.put(buffer.slice())
            }
            packed.rewind()
            bitmap.copyPixelsFromBuffer(packed)
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    companion object {
        const val TARGET_WIDTH = 1280f
        const val TARGET_HEIGHT = 720f
        const val JPEG_QUALITY = 60
    }
}
