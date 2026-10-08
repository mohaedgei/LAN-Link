package com.lanlink.app.control

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast

/**
 * An "invisible keyboard" input method: it renders no keys and simply
 * commits text received from the PC through the current input connection.
 *
 * One-time setup:
 *   Settings > System > Languages & input > On-screen keyboard >
 *   LAN-Link Text -> ON, then switch to it when typing remotely.
 */
class TextImeService : InputMethodService() {

    companion object {
        @Volatile
        private var instance: TextImeService? = null

        @Volatile
        private var appContext: Context? = null

        /** Commit remote text into the focused field (if the IME is active). */
        fun commit(text: String) {
            val ime = instance
            val ctx = appContext
            if (ime == null || ctx == null) {
                toast(ctx, "Enable the 'LAN-Link Text' keyboard to type remotely")
                return
            }
            val connection = ime.currentInputConnection
            if (connection != null) {
                connection.commitText(text, 1)
            } else {
                toast(ctx, "Switch the keyboard to 'LAN-Link Text' to type remotely")
            }
        }

        private fun toast(ctx: Context?, message: String) {
            val context = ctx ?: return
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appContext = applicationContext
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        instance = this
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (instance === this && currentInputConnection == null) {
            // Keep the instance; the input connection simply went away.
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Empty keyboard view: the IME stays active but occupies no space. */
    override fun onCreateInputView(): View = View(this)
}
