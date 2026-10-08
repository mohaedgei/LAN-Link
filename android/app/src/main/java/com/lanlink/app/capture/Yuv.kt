package com.lanlink.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.view.Surface
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Conversion helpers shared by the camera streamer and the QR scanner.
 * Handles all common YUV_420_888 layouts (planar and semi-planar).
 */
object Yuv {

    /** Convert a CameraX ImageProxy (YUV_420_888) into a NV21 byte array. */
    fun toNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        // ---- Y plane -------------------------------------------------
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuf.get(nv21, 0, ySize)
        } else {
            var pos = 0
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                yBuf.get(nv21, pos, width)
                pos += width
            }
        }

        // ---- Chroma (V first, then U -> NV21 interleaving) -----------
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        var out = ySize
        for (row in 0 until chromaHeight) {
            val vRow = row * vRowStride
            val uRow = row * uRowStride
            for (col in 0 until chromaWidth) {
                nv21[out++] = vBuf.get(vRow + col * vPixStride)
                nv21[out++] = uBuf.get(uRow + col * uPixStride)
            }
        }
        return nv21
    }

    /** Decode NV21 data into a Bitmap via a single JPEG round-trip. */
    fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap {
        val yuv = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Rotate a Bitmap by [degrees] (0 / 90 / 180 / 270). */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Map an ImageProxy rotationDegrees value to a Surface constant (not used yet, kept for future encoders). */
    fun rotationToSurface(degrees: Int): Int = when (degrees) {
        90 -> Surface.ROTATION_90
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_270
        else -> Surface.ROTATION_0
    }
}
