package io.github.lrq3000.utterlane.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ToggleButton
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
class RecordingPanel(context: Context, onStop: () -> Unit, onCancel: () -> Unit,
    onSettings: (CapturePhase) -> Unit) : LinearLayout(context) {
    private var themeMode = SettingsRepository.THEME_SYSTEM
    private val initialPalette = NativeBrandStyle.palette(context)
    private val foreground = initialPalette.text.toArgb()
    private val secondary = initialPalette.muted.toArgb()
    private val title = label(20f, foreground)
    private val settingsPrompt = KeyboardSettingsPrompt(context, onSettings)
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
    private val speakerTouchSize = dp(48)
    private val speakerTopInset = dp(8)
    val statusText = label(15f, foreground).apply { id = R.id.recording_status; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val speakerWarning = label(13f, foreground).apply {
        text = context.getString(R.string.speaker_model_needed)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val speakerSettings = Button(context).apply {
        id = R.id.recording_speaker_settings
        isAllCaps = false
        setOnClickListener {
            if (speakerControl?.requestSettingsAfterDelivery() == true) {
                showSpeakerSettingsAction(capturing = false)
                // Leaving a keyboard or onboarding window now would invalidate its text destination.
                // This action explicitly finishes capture, then its owner opens Settings after delivery.
                if (waveform.visibility == View.VISIBLE) onStop()
            }
        }
    }
    private val waveform = WaveformButton(context).apply {
        id = R.id.recording_done
        stateListAnimator = null
        setOnClickListener { onStop() }
        text = context.getString(R.string.capture_tap_finish)
        contentDescription = text
    }
    private var speakerControl: LiveSpeakerControl? = null
    private val speakers = ToggleButton(context).apply {
        id = R.id.recording_speakers
        stateListAnimator = null
        buttonDrawable = null
        foreground = null
        textOn = ""; textOff = ""; text = ""
        contentDescription = context.getString(R.string.home_speakers)
        tooltipText = context.getString(R.string.live_speakers_tooltip)
        setOnClickListener { speakerControl?.set(isChecked) }
    }
    private val waveformArea = FrameLayout(context).apply {
        addView(waveform, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Separate siblings keep the full waveform tappable to finish, except where the speaker button intercepts its own touches.
        addView(speakers, FrameLayout.LayoutParams(speakerTouchSize, speakerTouchSize, Gravity.TOP or Gravity.END).apply {
            topMargin = speakerTopInset
            marginEnd = dp(9)
        })
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
    private var speakerObserver: Job? = null

    init {
        orientation = VERTICAL
        setPadding(dp(20), 0, dp(20), dp(12))
        background = GradientDrawable().apply {
            setColor(initialPalette.surface.toArgb())
            cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
        }
        elevation = dp(12).toFloat()
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(settingsPrompt.gear, LayoutParams(dp(48), dp(48)))
            })
            addView(settingsPrompt, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(details); addView(recognition); addView(signal)
            addView(input); addView(inputWarning)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(waveformArea, LayoutParams(LayoutParams.MATCH_PARENT, dp(136)).apply { topMargin = dp(12) })
        processing.addView(percent)
        processing.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(16)).apply { topMargin = dp(16) })
        addView(processing, LayoutParams(LayoutParams.MATCH_PARENT, dp(136)).apply { topMargin = dp(12) })
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(speakerWarning)
        addView(speakerSettings, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(cancelButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
        applyPalette(initialPalette)
        render(CaptureSnapshot())
    }
    fun bind(scope: CoroutineScope, state: StateFlow<CaptureSnapshot>, speakers: LiveSpeakerControl? = null) {
        observer?.cancel()
        speakerControl = speakers
        speakerObserver?.cancel()
        speakerObserver = speakers?.let { control -> scope.launch {
            control.state.collect { enabled -> this@RecordingPanel.speakers.isChecked = enabled == true }
        } }
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
    fun dismissSettingsPrompt(): Boolean = settingsPrompt.dismiss()
    fun release() {
        observer?.cancel(); observer = null
        themeObserver?.cancel(); themeObserver = null
        speakerObserver?.cancel(); speakerObserver = null; speakerControl = null
        settingsPrompt.dismiss()
        keepScreenOn = false
    }

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
        speakerWarning.setTextColor(palette.text.toArgb())
        speakerSettings.backgroundTintList = null
        speakerSettings.background = NativeBrandStyle.tonalButton(palette, dp(12).toFloat())
        speakerSettings.setTextColor(palette.onContainer.toArgb())
        speakers.backgroundTintList = null
        speakers.background = waveformSpeakerBackground(context, palette)
        settingsPrompt.applyPalette(palette)
        waveform.applyPalette(palette)
    }

    private fun render(snapshot: CaptureSnapshot, showStatistics: Boolean = false) {
        settingsPrompt.update(snapshot.phase)
        input.text = AudioInputText.caption(context, snapshot)
        val warning = AudioInputText.warning(context, snapshot)
        // Do not reset an accessibility live region on every waveform publication.
        if (inputWarning.text.toString() != warning.orEmpty()) inputWarning.text = warning.orEmpty()
        inputWarning.visibility = if (warning == null) View.GONE else View.VISIBLE
        keepScreenOn = snapshot.phase !in listOf(CapturePhase.COMPLETE, CapturePhase.FAILED, CapturePhase.CANCELLED)
        val capturing = snapshot.phase == CapturePhase.CAPTURING
        waveform.visibility = if (capturing) View.VISIBLE else View.GONE
        waveformArea.visibility = waveform.visibility
        waveform.isEnabled = capturing
        val showSpeakers = capturing && speakerControl != null
        speakers.visibility = if (showSpeakers) View.VISIBLE else View.GONE
        // Keep the highest waveform peaks below the icon without increasing the panel height.
        waveform.plotTopInset = if (showSpeakers) speakerTopInset + speakerTouchSize else 0
        val showSpeakerWarning = snapshot.speakerModelNeeded && speakerControl != null &&
            snapshot.phase in setOf(CapturePhase.CAPTURING, CapturePhase.STOPPING, CapturePhase.PROCESSING)
        speakerWarning.visibility = if (showSpeakerWarning) View.VISIBLE else View.GONE
        speakerSettings.visibility = speakerWarning.visibility
        if (showSpeakerWarning) showSpeakerSettingsAction(capturing)
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

    private fun showSpeakerSettingsAction(capturing: Boolean) {
        val requested = speakerControl?.settingsRequested == true
        val label = if (requested) R.string.speaker_settings_queued
            else if (capturing) R.string.finish_and_open_speaker_settings
            else R.string.speaker_settings_after_transcription
        val text = context.getString(label)
        if (speakerSettings.text != text) speakerSettings.text = text
        speakerSettings.isEnabled = !requested
    }
    private fun label(size: Float, color: Int) = TextView(context).apply { textSize = size; setTextColor(color); gravity = Gravity.CENTER }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
