package com.translander.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.translander.R
import com.translander.asr.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** One bottom-panel presentation for IME and system/accessibility overlays. */
class RecordingPanel(context: Context, onStop: () -> Unit, onCancel: () -> Unit) : LinearLayout(context) {
    private val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val foreground = if (night) Color.WHITE else Color.rgb(30, 28, 40)
    private val secondary = if (night) Color.rgb(200, 190, 215) else Color.rgb(95, 85, 112)
    private val title = label(20f, foreground)
    private val details = label(13f, secondary)
    private val signal = label(14f, Color.rgb(218, 123, 38))
    val statusText = label(15f, foreground).apply { id = R.id.recording_status; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val waveform = WaveformButton(context).apply {
        id = R.id.recording_done
        setOnClickListener { onStop() }
        text = context.getString(R.string.capture_tap_finish)
        contentDescription = text
    }
    private val processing = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    private val percent = label(19f, foreground)
    private var observer: Job? = null

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(12))
        background = GradientDrawable().apply {
            setColor(if (night) Color.rgb(30, 26, 40) else Color.rgb(247, 243, 255))
            cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
        }
        elevation = dp(12).toFloat()
        addView(title); addView(details); addView(signal)
        addView(waveform, LayoutParams(LayoutParams.MATCH_PARENT, dp(136)).apply { topMargin = dp(12) })
        processing.addView(percent)
        processing.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(16)).apply { topMargin = dp(16) })
        addView(processing, LayoutParams(LayoutParams.MATCH_PARENT, dp(136)).apply { topMargin = dp(12) })
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(Button(context).apply { text = context.getString(R.string.overlay_cancel); setOnClickListener { onCancel() } }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        render(CaptureSnapshot())
    }
    fun bind(scope: CoroutineScope, state: StateFlow<CaptureSnapshot>) {
        observer?.cancel()
        observer = scope.launch { state.collect { render(it) } }
    }
    fun preview(text: String) { statusText.text = text.takeLast(300) }
    fun release() { observer?.cancel(); observer = null }

    private fun render(snapshot: CaptureSnapshot) {
        val capturing = snapshot.phase == CapturePhase.CAPTURING
        waveform.visibility = if (capturing) View.VISIBLE else View.GONE
        waveform.isEnabled = capturing
        processing.visibility = if (capturing) View.GONE else View.VISIBLE
        waveform.levels = snapshot.waveform
        title.text = context.getString(when (snapshot.phase) {
            CapturePhase.LOADING -> R.string.model_loading
            CapturePhase.CAPTURING -> R.string.capture_listening
            CapturePhase.STOPPING -> R.string.capture_stopping
            CapturePhase.PROCESSING -> R.string.capture_processing
            CapturePhase.COMPLETE -> R.string.capture_complete
            CapturePhase.FAILED -> R.string.capture_failed
            CapturePhase.CANCELLED -> R.string.stream_cancelled
        })
        val total = snapshot.capturedSamples / 16000.0
        val done = snapshot.processedSamples / 16000.0
        details.text = snapshot.modelName + if (capturing) " · ${formatTime(total)}" else if (snapshot.phase == CapturePhase.PROCESSING)
            " · " + context.getString(R.string.capture_audio_progress, done.toInt(), total.toInt()) else ""
        signal.visibility = if (capturing && snapshot.signal in listOf(CaptureSignal.LOW, CaptureSignal.NO_FRAMES, CaptureSignal.BLOCKED)) View.VISIBLE else View.GONE
        signal.text = context.getString(when (snapshot.signal) {
            CaptureSignal.BLOCKED -> R.string.capture_blocked
            CaptureSignal.NO_FRAMES -> R.string.capture_no_frames
            else -> R.string.capture_no_signal
        })
        val progress = snapshot.percent
        bar.isIndeterminate = progress == null
        if (progress != null) bar.progress = progress
        percent.text = if (progress == null) context.getString(R.string.capture_working) else "$progress%"
        snapshot.remainingSeconds?.takeIf { it > 0 && snapshot.phase == CapturePhase.PROCESSING }?.let {
            percent.text = "$progress% · " + context.getString(R.string.capture_eta, kotlin.math.ceil(it).toInt())
        }
        if (snapshot.error != null) statusText.text = snapshot.error
    }
    private fun label(size: Float, color: Int) = TextView(context).apply { textSize = size; setTextColor(color); gravity = Gravity.CENTER }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatTime(seconds: Double): String = "%02d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60)
}

/** The whole waveform is a native accessible Button. Only actual PCM levels drive its bars. */
private class WaveformButton(context: Context) : Button(context) {
    var levels = FloatArray(64)
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    init {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(97, 67, 170), Color.rgb(49, 106, 162))).apply { cornerRadius = 24 * resources.displayMetrics.density }
        setTextColor(Color.WHITE); textSize = 16f
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        setPadding(12, 12, 12, (16 * resources.displayMetrics.density).toInt())
        isAllCaps = false
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 24 * resources.displayMetrics.density
        val available = (width - 2 * padding).coerceAtLeast(1f)
        val center = height * 0.38f
        paint.strokeWidth = (available / levels.size * 0.55f).coerceAtLeast(2f)
        levels.forEachIndexed { index, level ->
            val x = padding + index * available / levels.size
            val amplitude = level * height * 0.25f
            canvas.drawLine(x, center - amplitude, x, center + amplitude.coerceAtLeast(1f), paint)
        }
    }
}
