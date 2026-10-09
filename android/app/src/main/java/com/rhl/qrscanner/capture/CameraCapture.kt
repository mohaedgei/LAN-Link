package com.rhl.qrscanner.capture

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
 *
 * v3.1.1: every lifecycle / camera call now happens on the MAIN thread
 * (CameraX requires it), and bind failures are REPORTED through
 * [onError] instead of being swallowed — so the PC log shows why no
 * frames arrive (camera busy, in use by another app, ...).
 */
class CameraCapture(
    private val context: Context,
    private val front: Boolean,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val owner = ServiceLifecycleOwner()
    private val worker = Executors.newSingleThreadExecutor()
    private val intervalMs = 90L
    private var provider: ProcessCameraProvider? = null
    private var lastSent = 0L

    @Volatile
    private var stopped = false

    fun start() {
        stopped = false
        // CameraX + LifecycleRegistry must be touched from the main thread.
        ContextCompat.getMainExecutor(context).execute {
            if (stopped) return@execute
            startOnMain()
        }
    }

    private fun startOnMain() {
        try {
            owner.resume()
        } catch (t: Throwable) {
            onError("lifecycle: ${t.message}")
            return
        }
        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (t: Throwable) {
            onError("camerax-init: ${t.message}")
            return
        }
        future.addListener({
            if (stopped) return@addListener
            bindNow(future)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindNow(future: com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>) {
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
        } catch (t: Throwable) {
            // Camera busy / in use / unavailable — TELL the PC, don't hide it.
            onError("camera: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (stopped || now - lastSent < intervalMs) {
            image.close()
            return
        }
        lastSent = now

        val rotation = image.imageInfo.rotationDegrees
        val width = image.width
        val height = image.height
        val nv21 = try {
            Yuv.toNv21(image)
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        } ?: return

        try {
            var bitmap = Yuv.nv21ToBitmap(nv21, width, height)
            if (rotation != 0) {
                bitmap = Yuv.rotate(bitmap, rotation)
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            onFrame(out.toByteArray())
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        stopped = true
        try {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    provider?.unbindAll()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
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
