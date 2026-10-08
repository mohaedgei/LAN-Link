package com.lanlink.app

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QL1 payload codec — the encrypted QR format shared by:
 *
 *   - the LAN-Link script   (python lanlink.py, option 2 / 3)
 *   - the website           (https://c4sf4qh0-d.space-z.ai, /api/qr/encrypt)
 *   - this app              (decrypt only)
 *
 * Wire format:  QL1. + base64url( nonce[12] || ciphertext || tag[16] )
 * Cipher:       AES-256-GCM, key = SHA-256(secret), 128-bit auth tag.
 *
 * The secret is baked into the app binary, so only this app can turn a
 * QR back into the command inside. Any tampering breaks the auth tag.
 */
object Ql1 {

    /** Must match QLINK_SECRET on the server and SECRET in core/qrcrypto.py. */
    private const val SECRET = "qlink-secret-v1-do-not-share"

    private const val PREFIX = "QL1."
    private const val NONCE_LEN = 12
    private const val TAG_LEN = 16

    /**
     * Decrypt a QL1 payload string.
     * Returns the JSON object {v, cmd, host, port, t, exp} or null if the
     * payload is not a valid/tamper-free QL1 code.
     */
    fun decrypt(payload: String): JSONObject? {
        if (!payload.startsWith(PREFIX)) return null
        return try {
            val data = Base64.decode(
                payload.substring(PREFIX.length),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            if (data.size <= NONCE_LEN + TAG_LEN) return null

            val nonce = data.copyOfRange(0, NONCE_LEN)
            val cipherText = data.copyOfRange(NONCE_LEN, data.size - TAG_LEN)
            val tag = data.copyOfRange(data.size - TAG_LEN, data.size)

            val key = MessageDigest.getInstance("SHA-256")
                .digest(SECRET.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LEN * 8, nonce)
            )
            // JCE expects ciphertext||tag on input
            val plain = cipher.doFinal(cipherText + tag)
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null // wrong key, tampered payload, or not a QL1 code at all
        }
    }
}
