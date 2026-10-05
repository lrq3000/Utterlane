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
            val lexicalStart = span.text.indexOfFirst { it.isLetterOrDigit() }
            // A correction can change a word's owner while its original trailing
            // punctuation keeps source metadata. Punctuation belongs to the
            // corrected lexical owner and cannot establish a separate turn.
            if (lexicalStart < 0) {
                output.append(span.text)
                continue
            }
            if (speaker != span.speaker) {
                var text = span.text
                if (emitted) {
                    // One source span may contain both trailing punctuation
                    // and the next speaker's words: switch only at the words.
                    output.append(text.take(lexicalStart).trimEnd()).append('\n')
                    text = text.drop(lexicalStart)
                }
                output.append(label(span.speaker)).append(": ").append(text.trimStart())
                speaker = span.speaker
            } else output.append(span.text)
            emitted = true
        }
        return output.toString()
    }
}
