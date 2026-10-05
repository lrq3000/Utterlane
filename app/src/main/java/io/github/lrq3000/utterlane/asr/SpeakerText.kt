package io.github.lrq3000.utterlane.asr

/** Corrections own wording; speaker labels are formatting metadata applied afterward. */
class SpeakerText(private val corrections: StreamingCorrections, private val label: (Int) -> String) {
    private var speaker: Int? = null
    private var emitted = false

    fun accept(spans: List<SpeechSpan>): List<String> = format(corrections.acceptSpans(spans))
        .takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()

    fun finish(): String = format(corrections.finishSpans())

    private fun format(spans: List<SpeechSpan>): String {
        val output = StringBuilder()
        for (span in spans) {
            if (span.text.isBlank()) {
                output.append(span.text)
                continue
            }
            if (speaker != span.speaker) {
                if (emitted) output.append('\n')
                output.append(label(span.speaker)).append(": ").append(span.text.trimStart())
                speaker = span.speaker
            } else output.append(span.text)
            emitted = true
        }
        return output.toString()
    }
}
