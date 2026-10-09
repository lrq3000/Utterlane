package io.github.lrq3000.utterlane.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local technical evidence; no speech, raw device addresses or automatic export. */
class MicrophoneDiagnostics {
    data class Snapshot(val options: MicrophoneOptions? = null, val active: Boolean = false,
        val stage: String = "No microphone capture attempted", val format: String = "",
        val route: String = "", val input: String = "", val effects: String = "") {
        fun text(): String = buildString {
            appendLine(stage)
            options?.let {
                appendLine("Requested source: ${it.source}")
                appendLine("Requested route/mode: ${it.route} / ${it.mode}")
                appendLine("Requested preprocessing: ${it.preprocessing}")
                appendLine("Transcription gain: ${it.gain} (original PCM is preserved)")
            }
            listOf(format, route, input, effects).filter { it.isNotBlank() }.forEach { appendLine(it) }
            append("AudioRecord PCM format is not a Bluetooth codec/link measurement. Java effect states do not prove OEM DSP bypass.")
        }
    }
    private val mutable = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = mutable.asStateFlow()
    private var owner: Any? = null

    @Synchronized fun begin(options: MicrophoneOptions): Session {
        val token = Any()
        owner = token
        mutable.value = Snapshot(options, true, "Preparing microphone capture")
        return Session(token)
    }
    @Synchronized private fun update(token: Any, change: (Snapshot) -> Snapshot) {
        if (owner === token && mutable.value.active) mutable.value = change(mutable.value)
    }
    inner class Session internal constructor(private val token: Any) {
        fun format(source: Int, sampleRate: Int, channels: Int, sessionId: Int) = update(token) {
            it.copy(stage = "Microphone capturing", format = "Observed AudioRecord: source=$source, ${sampleRate} Hz, $channels channel(s), session=$sessionId")
        }
        fun route(detail: String) = update(token) { it.copy(route = redact(detail)) }
        fun input(state: CaptureInputState) = update(token) {
            val actual = state.actual
            it.copy(input = "Observed input: " + when {
                actual == null -> "not yet reported"
                actual.isPhone -> "Phone microphone"
                else -> "${redact(actual.name)} (input port=${actual.inputId})"
            } + (state.fallbackReason?.let { reason -> "; fallback=$reason; Phone frames confirmed=${state.receivingFallback}" } ?: ""))
        }
        internal fun effects(status: List<MicrophoneEffectStatus>) = update(token) {
            it.copy(effects = status.joinToString("\n") { effect ->
                "${effect.kind}: available=${effect.available}, requested=${effect.requested ?: "system"}, actual=${effect.actual ?: "unknown"}, control=${effect.control ?: "unknown"} (${effect.detail})"
            })
        }
        fun finish(failure: String? = null) = update(token) {
            it.copy(active = false, stage = if (failure == null) "Microphone capture ended" else "Microphone capture ended: ${redact(failure)}")
        }
    }
    private fun redact(text: String) = MAC.replace(text, "[redacted]")
    companion object { private val MAC = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}") }
}
