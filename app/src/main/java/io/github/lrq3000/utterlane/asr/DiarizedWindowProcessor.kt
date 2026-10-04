package io.github.lrq3000.utterlane.asr

import java.io.Closeable

interface SpeakerProbabilityStream : Closeable {
    fun push(samples: ShortArray, final: Boolean): FloatArray
}

/** Per-recording state. ASR may re-read overlap; the speaker cache must never do so. */
class DiarizedWindowProcessor(
    private val backend: RecognitionBackend,
    private val stream: SpeakerProbabilityStream,
    count: Int,
    private val textOnly: Boolean = false
) : Closeable {
    private val timeline = SpeakerTimeline(count)
    private var fed = 0L
    private var finished = false

    fun process(window: AudioWindow): List<SpeechSpan> {
        check(!finished)
        val end = window.startSample + window.samples.size
        require(window.startSample <= fed && fed <= end && window.ownedStart >= window.startSample && window.ownedEnd <= end)
        timeline.discardBefore(window.startSample)
        val fresh = window.samples.copyOfRange((fed - window.startSample).toInt(), window.samples.size)
        timeline.append(stream.push(fresh, window.isFinal))
        fed = end
        finished = window.isFinal
        if (window.isFinal) timeline.finishAt(window.ownedEnd)
        // The one-second right context covers the native streaming lookahead.
        // Failing explicitly is preferable to emitting confidently wrong labels.
        check(timeline.endSample >= window.ownedEnd) {
            "Diarization boundary: labeled=${timeline.endSample}, owned=${window.ownedEnd}, fed=$fed, final=${window.isFinal}"
        }
        if (textOnly) return timeline.turns(window.ownedStart, window.ownedEnd).mapNotNull { turn ->
            val result = backend.transcribeWindow(window.samples.copyOfRange(
                (turn.start - window.startSample).toInt(), (turn.end - window.startSample).toInt()))
            val text = result.text.orEmpty().trim()
            text.takeIf { it.isNotEmpty() }?.let { SpeechSpan(it, turn.speaker) }
        }

        val result = backend.transcribeWindow(window.samples)
        val spans = mutableListOf<SpeechSpan>()
        val text = StringBuilder()
        var speaker = -1
        fun flushSpan() {
            if (text.isNotEmpty()) spans += SpeechSpan(text.toString(), speaker)
            text.setLength(0)
        }
        WindowText.forEachOwnedWord(result.tokens, result.timestamps, window) { word, position ->
            if (word.isNotBlank()) {
                val next = timeline.speakerAt(position)
                if (next != speaker) { flushSpan(); speaker = next }
                if (text.isNotEmpty()) text.append(' ')
                text.append(word.trim())
            }
        }
        flushSpan()
        return spans
    }
    override fun close() = stream.close()
}
