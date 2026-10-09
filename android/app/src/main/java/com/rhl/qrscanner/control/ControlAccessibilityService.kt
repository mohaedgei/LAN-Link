package com.rhl.qrscanner.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * Executes remote control commands sent from the PC viewer:
 * taps, swipes, pinch zoom, global keys (Back / Home / Recents).
 *
 * Requires the user to enable it once:
 *   Settings > Accessibility > LAN-Link Control -> ON
 */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: ControlAccessibilityService? = null

        /** True when the user has enabled the service in system settings. */
        fun isReady(): Boolean = instance != null

        /** Entry point used by StreamService for incoming control JSON. */
        fun handle(json: JSONObject) {
            val svc = instance ?: return
            when (json.optString("type")) {
                "tap" -> svc.tap(
                    json.optDouble("x", 0.5).toFloat(),
                    json.optDouble("y", 0.5).toFloat()
                )
                "swipe" -> svc.swipe(
                    json.optDouble("x1", 0.0).toFloat(),
                    json.optDouble("y1", 0.0).toFloat(),
                    json.optDouble("x2", 1.0).toFloat(),
                    json.optDouble("y2", 1.0).toFloat(),
                    json.optLong("ms", 300L)
                )
                "pinch" -> svc.pinch(json.optString("dir", "in"))
                "key" -> svc.key(json.optString("value", ""))
                "text" -> TextImeService.commit(json.optString("value", ""))
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ------------------------------------------------------------------
    // Gestures
    // ------------------------------------------------------------------

    private fun sx(nx: Float): Float = nx * resources.displayMetrics.widthPixels
    private fun sy(ny: Float): Float = ny * resources.displayMetrics.heightPixels

    fun tap(nx: Float, ny: Float) {
        val x = sx(nx)
        val y = sy(ny)
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.4f, y + 0.4f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1n: Float, y1n: Float, x2n: Float, y2n: Float, ms: Long) {
        val path = Path().apply {
            moveTo(sx(x1n), sy(y1n))
            lineTo(sx(x2n), sy(y2n))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.coerceIn(80, 2000))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun pinch(dir: String) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val zoomIn = dir == "in"
        val rStart = if (zoomIn) w * 0.12f else w * 0.35f
        val rEnd = if (zoomIn) w * 0.35f else w * 0.12f

        val finger1 = Path().apply {
            moveTo(cx - rStart, cy)
            lineTo(cx - rEnd, cy)
        }
        val finger2 = Path().apply {
            moveTo(cx + rStart, cy)
            lineTo(cx + rEnd, cy)
        }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(finger1, 0, 400))
            .addStroke(GestureDescription.StrokeDescription(finger2, 0, 400))
        dispatchGesture(builder.build(), null, null)
    }

    fun key(value: String) {
        val action = when (value) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return
        }
        performGlobalAction(action)
    }
}
