package io.github.lrq3000.utterlane.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.ui.theme.BrandPalette
import io.github.lrq3000.utterlane.ui.theme.NativeBrandStyle
import io.github.lrq3000.utterlane.asr.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** One bottom-panel presentation for IME and system/accessibility overlays. */
class RecordingPanel(context: Context, onStop: () -> Unit, onCancel: () -> Unit) : LinearLayout(context) {
    private var themeMode = SettingsRepository.THEME_SYSTEM
    private val initialPalette = NativeBrandStyle.palette(context)
    private val foreground = initialPalette.text.toArgb()
    private val secondary = initialPalette.muted.toArgb()
    private val title = label(20f, foreground)
    private val details = label(13f, secondary)
    private val recognition = label(13f, secondary)
    private val signal = label(14f, initialPalette.warning.toArgb())
    private val input = label(12f, secondary).apply { id = R.id.recording_input }
    private val inputWarning = label(13f, foreground).apply {
        id = R.id.recording_input_warning
        // Announce the degradation once when text changes, without stealing
        // focus from Stop or requiring the user to attend to the screen.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
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
    private val cancelButton = Button(context).apply {
        text = context.getString(R.string.overlay_cancel)
        isAllCaps = false
        setOnClickListener { onCancel() }
    }
    private var observer: Job? = null
    private var themeObserver: Job? = null

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(12))
        background = GradientDrawable().apply {
            setColor(initialPalette.surface.toArgb())
            cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
        }
        elevation = dp(12).toFloat()
        addView(title); addView(details); addView(recognition); addView(signal)
        addView(input); addView(inputWarning)
        addView(waveform, LayoutParams(LayoutParams.MATCH_PARENT, dp(136)).apply { topMargin = dp(12) })
        processing.addView(percent)
        processing.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(16)).apply { topMargin = dp(16) })
        addView(processing, LayoutParams(LayoutParams.MATCH_PARENT, dp(136)).apply { topMargin = dp(12) })
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(cancelButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
        applyPalette(initialPalette)
        render(CaptureSnapshot())
    }
    fun bind(scope: CoroutineScope, state: StateFlow<CaptureSnapshot>) {
        observer?.cancel()
        observer = scope.launch {
            // Redraw even if only the display preference changes (no new audio/progress).
            // The existing observer owns both subscriptions, so release cancels both.
            state.combine(UtterlaneApp.instance.settingsRepository.showTranscriptionStreamStatistics.distinctUntilChanged()) {
                snapshot, showStatistics -> snapshot to showStatistics
            }.collect { (snapshot, showStatistics) -> render(snapshot, showStatistics) }
        }
        // Native IME/accessibility contexts do not inherit Compose's explicit
        // light/dark preference. Observe it without blocking the main thread.
        themeObserver?.cancel()
        themeObserver = scope.launch {
            UtterlaneApp.instance.settingsRepository.themeMode.collect { mode ->
                themeMode = mode
                applyPalette(NativeBrandStyle.palette(context, mode))
            }
        }
    }
    fun preview(text: String) { statusText.text = text.takeLast(300) }
    fun release() { observer?.cancel(); observer = null; themeObserver?.cancel(); themeObserver = null; keepScreenOn = false }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPalette(NativeBrandStyle.palette(context, themeMode))
    }

    private fun applyPalette(palette: BrandPalette) {
        (background as GradientDrawable).setColor(palette.surface.toArgb())
        title.setTextColor(palette.text.toArgb())
        details.setTextColor(palette.muted.toArgb())
        recognition.setTextColor(palette.muted.toArgb())
        signal.setTextColor(palette.warning.toArgb())
        input.setTextColor(palette.muted.toArgb())
        inputWarning.setTextColor((if (palette.dark) io.github.lrq3000.utterlane.ui.theme.RecordingRedLight
            else io.github.lrq3000.utterlane.ui.theme.RecordingRed).toArgb())
        statusText.setTextColor(palette.text.toArgb())
        percent.setTextColor(palette.text.toArgb())
        bar.progressTintList = ColorStateList.valueOf(palette.primary.toArgb())
        bar.indeterminateTintList = bar.progressTintList
        bar.progressBackgroundTintList = ColorStateList.valueOf(palette.container.toArgb())
        cancelButton.backgroundTintList = null
        cancelButton.background = NativeBrandStyle.tonalButton(palette, dp(12).toFloat())
        cancelButton.setTextColor(palette.onContainer.toArgb())
        waveform.applyPalette(palette)
    }

    private fun render(snapshot: CaptureSnapshot, showStatistics: Boolean = false) {
        input.text = AudioInputText.caption(context, snapshot)
        val warning = AudioInputText.warning(context, snapshot)
        // Do not reset an accessibility live region on every waveform publication.
        if (inputWarning.text.toString() != warning.orEmpty()) inputWarning.text = warning.orEmpty()
        inputWarning.visibility = if (warning == null) View.GONE else View.VISIBLE
        keepScreenOn = snapshot.phase !in listOf(CapturePhase.COMPLETE, CapturePhase.FAILED, CapturePhase.CANCELLED)
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
        details.text = if (showStatistics) snapshot.modelName + "\n" + RecognitionStatusText.backlog(context, snapshot) else snapshot.modelName
        recognition.text = when {
            snapshot.recognitionFailure != null -> context.getString(if (capturing) R.string.recording_recognition_unavailable else R.string.recording_processing_unavailable)
            snapshot.modelPreparing && capturing -> context.getString(R.string.recording_model_loading)
            snapshot.modelPreparing && snapshot.phase == CapturePhase.PROCESSING -> context.getString(R.string.recording_model_loading_saved)
            showStatistics -> RecognitionStatusText.activity(context, snapshot.recognition)
            else -> ""
        }
        recognition.visibility = if (recognition.text.isEmpty()) View.GONE else View.VISIBLE
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
}
