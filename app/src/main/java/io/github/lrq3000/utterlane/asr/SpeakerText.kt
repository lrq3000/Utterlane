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
            // punctuation keeps source metadata. Trailing punctuation belongs to the
            // corrected lexical owner and cannot establish a separate turn.
            if (lexicalStart < 0) {
                output.append(span.text)
                continue
            }
            if (speaker != span.speaker) {
                var text = span.text
                if (emitted) {
                    // Split trailing punctuation from opening marks belonging
                    // to the incoming word, e.g. "! ¿Qué" after a replacement.
                    val incomingStart = incomingPrefixStart(text, lexicalStart)
                    output.append(text.take(incomingStart).trimEnd()).append('\n')
                    text = text.drop(incomingStart)
                }
                output.append(label(span.speaker)).append(": ").append(text.trimStart())
                speaker = span.speaker
            } else output.append(span.text)
            emitted = true
        }
        return output.toString()
    }

    private fun incomingPrefixStart(text: String, lexicalStart: Int): Int {
        for (index in 0 until lexicalStart) {
            val character = text[index]
            if (isOpeningPunctuation(character)) return index
            // Straight quotes have no Unicode opening/closing distinction.
            // A quote before the word or after a separator opens its prefix;
            // an attached closing quote in "!\" Next" remains with the old word.
            if ((character == '"' || character == '\'') &&
                ((index > 0 && text[index - 1].isWhitespace()) || index + 1 == lexicalStart ||
                    isOpeningPunctuation(text[index + 1]))) return index
        }
        return lexicalStart
    }

    private fun isOpeningPunctuation(character: Char): Boolean = character == '¿' || character == '¡' ||
        character.category == CharCategory.START_PUNCTUATION || character.category == CharCategory.INITIAL_QUOTE_PUNCTUATION
}
