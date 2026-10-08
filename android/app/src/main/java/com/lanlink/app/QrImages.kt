package com.lanlink.app

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Decode a QR code from a picture (gallery / screenshot / received photo). */
object QrImages {

    /** Returns the raw QR text, or null when no QR code is found. */
    fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            null
        }
    }
}
