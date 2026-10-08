package com.lanlink.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Front / back camera streaming engine built on CameraX.
 *
 * Frames arrive as YUV_420_888, get converted to NV21, rotated to the
 * natural orientation, encoded as JPEG (720p target) and pushed to
 * [onFrame] at ~10-11 fps.
 */
class CameraCapture(
    private val context: Context,
    private val front: Boolean,
    private val onFrame: (ByteArray) -> Unit,
) : ImageAnalysis.Analyzer {

    private val owner = ServiceLifecycleOwner()
    private val worker = Executors.newSingleThreadExecutor()
    private val intervalMs = 90L
    private var provider: ProcessCameraProvider? = null
    private var lastSent = 0L

    fun start() {
        // Must be RESUMED before bindToLifecycle()
        owner.resume()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(worker, this)
                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (front) CameraSelector.LENS_FACING_FRONT
                        else CameraSelector.LENS_FACING_BACK
                    )
                    .build()
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, selector, analysis)
            } catch (_: Exception) {
                // Camera busy or unavailable; the service keeps running.
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent < intervalMs) {
            image.close()
            return
        }
        lastSent = now

        val rotation = image.imageInfo.rotationDegrees
        val width = image.width
        val height = image.height
        val nv21 = Yuv.toNv21(image)
        image.close()

        try {
            var bitmap = Yuv.nv21ToBitmap(nv21, width, height)
            if (rotation != 0) {
                bitmap = Yuv.rotate(bitmap, rotation)
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            onFrame(out.toByteArray())
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    provider?.unbindAll()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        owner.destroy()
        worker.shutdownNow()
    }

    /**
     * A LifecycleOwner owned by the capture engine (the stream runs while
     * the app is in the background, so we cannot bind to an Activity or to
     * the ProcessLifecycleOwner, which reports STOPPED when backgrounded).
     */
    private class ServiceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    companion object {
        const val JPEG_QUALITY = 60
    }
}
