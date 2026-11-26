package io.github.lrq3000.utterlane.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.asr.CaptureSnapshot
import io.github.lrq3000.utterlane.asr.LiveSpeakerControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Bottom overlay that leaves the original editor focused. Accessibility needs no extra overlay permission. */
class RecordingOverlay(private val context: Context, private val onSettings: (CapturePhase) -> Unit) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var panel: RecordingPanel? = null
    var onDoneClick: (() -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null

    fun show() {
        if (overlayView != null) return
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = RecordingPanel(context, { onDoneClick?.invoke() }, { onCancelClick?.invoke() }, onSettings)
        overlayView = view; panel = view
        // Navigation/cutout padding keeps the large stop target above system controls.
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, (12 * context.resources.displayMetrics.density).toInt() + bottom)
            insets
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (context is AccessibilityService) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        windowManager?.addView(view, params)
    }
    fun bind(scope: CoroutineScope, metrics: StateFlow<CaptureSnapshot>, speakers: LiveSpeakerControl) {
        panel?.bind(scope, metrics, speakers)
    }
    fun setStatus(text: String) { panel?.preview(text) }
    fun dismissSettingsPrompt(): Boolean = panel?.dismissSettingsPrompt() == true
    fun hide() {
        panel?.release()
        overlayView?.let { try { windowManager?.removeView(it) } catch (_: IllegalArgumentException) { /* already removed by the window manager */ } }
        overlayView = null; panel = null; windowManager = null
    }
}
