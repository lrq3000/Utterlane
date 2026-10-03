package io.github.lrq3000.utterlane.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import kotlinx.coroutines.CoroutineScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object RecordingUIBuilder {
    data class RecordingUI(val view: LinearLayout, val statusText: TextView, val panel: RecordingPanel)
    fun createRecordingBar(context: Context, onDoneClick: () -> Unit, onCancelClick: () -> Unit): RecordingUI {
        val panel = RecordingPanel(context, onDoneClick, onCancelClick)
        return RecordingUI(panel, panel.statusText, panel)
    }
}

/** Bottom overlay that leaves the original editor focused. Accessibility needs no extra overlay permission. */
class RecordingOverlay(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var panel: RecordingPanel? = null
    var onDoneClick: (() -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null

    fun show() {
        if (overlayView != null) return
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ui = RecordingUIBuilder.createRecordingBar(context, { onDoneClick?.invoke() }, { onCancelClick?.invoke() })
        overlayView = ui.view; panel = ui.panel
        // Navigation/cutout padding keeps the large stop target above system controls.
        ViewCompat.setOnApplyWindowInsetsListener(ui.view) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, (12 * context.resources.displayMetrics.density).toInt() + bottom)
            insets
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (context is AccessibilityService) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        windowManager?.addView(ui.view, params)
    }
    fun bind(scope: CoroutineScope, session: MicrophoneSession) { panel?.bind(scope, session.telemetry.state) }
    fun setStatus(text: String) { panel?.preview(text) }
    fun hide() {
        panel?.release()
        overlayView?.let { try { windowManager?.removeView(it) } catch (_: IllegalArgumentException) { /* already removed by the window manager */ } }
        overlayView = null; panel = null; windowManager = null
    }
}
