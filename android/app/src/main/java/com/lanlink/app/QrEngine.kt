package com.lanlink.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * The scanning brain (v2.1 — rebuilt from scratch).
 *
 * Camera path:
 *   The Y (luminance) plane of the CameraX frame is fed DIRECTLY into
 *   ZXing as a PlanarYUVLuminanceSource — no JPEG round-trip, no bitmap
 *   allocation per frame. Only the centre square is scanned, rotation is
 *   handled with ZXing's own bitmap rotation. This is what makes live
 *   scanning finally fast and reliable.
 *
 * Picture path (gallery / screenshot):
 *   Multiple attempts: sensible scales, all 4 rotations and BOTH
 *   binarizers (Hybrid + GlobalHistogram), with TRY_HARDER. Huge photos
 *   are downscaled first; tiny QRs are upscaled. This is what makes
 *   "decode from picture" actually find codes.
 */
object QrEngine {

    private val reader = MultiFormatReader()

    private fun hints(): MutableMap<DecodeHintType, Any> = hashMapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    // ------------------------------------------------------------------
    // Camera frames
    // ------------------------------------------------------------------

    /**
     * Decode a QR from the camera frame. Returns the raw text or null.
     * Must be called BEFORE image.close() — this method reads the planes.
     */
    @OptIn(ExperimentalGetImage::class)
    fun fromCamera(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buf = plane.buffer
        val width = image.width
        val height = image.height
        val rotation = image.imageInfo.rotationDegrees

        val data = ByteArray(buf.remaining())
        buf.get(data)

        // centre square crop (in the unrotated sensor orientation):
        // scanning ~70% of the short side keeps speed high and noise out
        val side = (minOf(width, height) * 0.72f).toInt().coerceAtLeast(120)
        val left = (width - side) / 2
        val top = (height - side) / 2

        val source = PlanarYUVLuminanceSource(
            data, plane.rowStride, height, left, top, side, side, false
        )
        var bitmap = BinaryBitmap(HybridBinarizer(source))

        // TRY_HARDER must be set for decodeWithState to behave like decode(hints)
        reader.setHints(hints())

        // rotate to upright — ZXing only rotates CCW, so map:
        //   0 -> as-is | 90 -> 180+CCW (=CW90) | 180 -> 180 | 270 -> CCW90
        bitmap = when (rotation) {
            90 -> try { bitmap.rotateCounterClockwise().rotateCounterClockwise().rotateCounterClockwise() } catch (_: Exception) { bitmap }
            180 -> try { bitmap.rotateCounterClockwise().rotateCounterClockwise() } catch (_: Exception) { bitmap }
            270 -> try { bitmap.rotateCounterClockwise() } catch (_: Exception) { bitmap }
            else -> bitmap
        }

        // upright first; if the OS gave a weird rotation value, try the
        // other orientations too (cheap on the small cropped region)
        for (attempt in 0..3) {
            val candidate = when (attempt) {
                1 -> try { bitmap.rotateCounterClockwise() } catch (_: Exception) { return null }
                2 -> try { bitmap.rotateCounterClockwise().rotateCounterClockwise() } catch (_: Exception) { return null }
                3 -> try { bitmap.rotateCounterClockwise().rotateCounterClockwise().rotateCounterClockwise() } catch (_: Exception) { return null }
                else -> bitmap
            }
            try {
                return reader.decodeWithState(candidate).text
            } catch (_: NotFoundException) {
                reader.reset()
            } catch (_: Exception) {
                reader.reset()
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Pictures (gallery / screenshot / saved QR PNG)
    // ------------------------------------------------------------------

    /** Decode a QR from a picture. Returns the raw text or null. */
    fun fromBitmap(original: Bitmap): String? {
        // cap huge photos: ZXing becomes unreliable past ~1500px
        val bitmap = if (maxOf(original.width, original.height) > 1500) {
            val scale = 1500f / maxOf(original.width, original.height)
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else original

        val scales = if (maxOf(bitmap.width, bitmap.height) < 400)
            listOf(1.0f, 2.0f, 3.0f)   // small thumbnails: upscale
        else
            listOf(1.0f, 0.5f, 0.75f, 1.4f)

        for (scale in scales) {
            val scaled = if (scale == 1.0f) bitmap
            else Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            for (rotation in intArrayOf(0, 90, 180, 270)) {
                val rotated = rotated(scaled, rotation)
                val text = tryAllBinarizers(rotated)
                if (text != null) return text
            }
        }
        return null
    }

    /** Try Hybrid and GlobalHistogram binarizers on one bitmap. */
    private fun tryAllBinarizers(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)

        for (useGlobal in booleanArrayOf(true, false)) {
            val binary = if (useGlobal) BinaryBitmap(GlobalHistogramBinarizer(source))
            else BinaryBitmap(HybridBinarizer(source))
            try {
                return reader.decode(binary, hints()).text
            } catch (_: NotFoundException) {
                // next strategy
            } catch (_: Exception) {
                // corrupt region — next strategy
            } finally {
                reader.reset()
            }
        }
        return null
    }

    /** Decode raw image bytes (already-read file/stream) — helper. */
    fun fromBytes(bytes: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun rotated(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
