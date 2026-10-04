package io.github.lrq3000.utterlane.asr

/** Corrections run on speech only; their trailing buffer belongs to one speaker. */
class SpeakerText(private val corrections: StreamingCorrections, private val label: (Int) -> String) {
    private var speaker: Int? = null
    private var needsLabel = true
    private var emitted = false
    fun accept(spans: List<SpeechSpan>): List<String> {
        val result = mutableListOf<String>()
        for (span in spans) {
            if (span.text.isBlank()) continue
            if (speaker != span.speaker) {
                format(corrections.finish()).takeIf { it.isNotEmpty() }?.let { result += it }
                speaker = span.speaker; needsLabel = true
            }
            format(corrections.accept(span.text)).takeIf { it.isNotEmpty() }?.let { result += it }
        }
        return result
    }
    fun finish(): String = format(corrections.finish())
    private fun format(text: String): String {
        if (text.isBlank()) return ""
        val prefix = if (needsLabel) (if (emitted) "\n" else "") + label(checkNotNull(speaker)) + ": " else ""
        needsLabel = false; emitted = true
        return prefix + text
    }
}
