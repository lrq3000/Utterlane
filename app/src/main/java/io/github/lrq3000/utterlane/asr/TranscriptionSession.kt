package io.github.lrq3000.utterlane.asr

import android.util.Log
import io.github.lrq3000.utterlane.settings.RuntimeOptions

/** File and live sources share ownership, corrections and append-only result delivery. */
class TranscriptionSession(
    val store: TranscriptStore,
    private val corrections: StreamingCorrections,
    private val onSegment: suspend (String) -> Unit,
    decode: suspend (AudioWindow) -> String,
    private val onClosed: () -> Unit = {},
    private val onProcessed: (Long, Long) -> Unit = { _, _ -> },
    private val decodeSpeakers: (suspend (AudioWindow) -> List<SpeechSpan>)? = null,
    speakerLabel: (Int) -> String = { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" },
    options: RuntimeOptions = RuntimeOptions()
) : java.io.Closeable {
    private val speakerText = if (decodeSpeakers != null) SpeakerText(corrections, speakerLabel) else null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val segmenter = AudioSegmenter(flushPendingOnFinish = decodeSpeakers != null, options = options) { window ->
        val started = System.nanoTime()
        if (decodeSpeakers != null) {
            for (text in checkNotNull(speakerText).accept(decodeSpeakers.invoke(window))) emit(text)
        } else emit(corrections.accept(decode(window)))
        onProcessed(window.ownedEnd, (System.nanoTime() - started) / 1000000)
        Log.i("TranscriptionSession", "Segment ${window.ownedStart}..${window.ownedEnd}; input=${window.samples.size}; completed=${store.segments}")
    }
    private var finished = false
    suspend fun accept(samples: ShortArray) {
        check(!closed.get()) { "Transcription session is closed" }
        segmenter.accept(samples)
    }
    suspend fun finish() {
        if (finished || closed.get()) return
        try {
            segmenter.finish()
            emit(speakerText?.finish() ?: corrections.finish())
            finished = true
        } finally { close() }
    }
    override fun close() { if (closed.compareAndSet(false, true)) onClosed() }
    private suspend fun emit(text: String) {
        if (text.isBlank()) return
        store.append(text)
        onSegment(text.trimEnd())
    }
}

/** Raw trailing words are retained, so multi-word corrections can span audio windows. */
class StreamingCorrections(rules: List<DictionaryManager.ReplacementRule>) {
    private val compiled = rules.map {
        Pair(Regex("\\b${Regex.escape(it.from)}\\b", if (it.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)), it.to)
    }
    private val hold = rules.maxOfOrNull { it.from.length } ?: 0
    private var pending = AttributedText("", IntArray(0))

    fun accept(text: String): String = acceptSpans(listOf(SpeechSpan(text.trim(), -1))).joinToString("") { it.text }

    /** Labels share the raw correction buffer; they never cause a flush or enter a regex. */
    fun acceptSpans(spans: List<SpeechSpan>): List<SpeechSpan> {
        val incoming = AttributedText.fromSpans(spans)
        if (incoming.text.isNotBlank()) pending = pending.append(incoming)
        if (hold == 0) return finishSpans()
        val limit = (pending.text.length - hold - 1).coerceAtLeast(0)
        var cut = pending.text.lastIndexOf(' ', limit).coerceAtLeast(0)
        for ((pattern, _) in compiled) {
            for (match in pattern.findAll(pending.text)) {
                if (match.range.first < cut && match.range.last >= cut) cut = match.range.first
            }
        }
        val prefix = pending.slice(0, cut)
        pending = pending.slice(cut, pending.text.length).trim(startOnly = true)
        return correct(prefix)
    }
    fun finish(): String = finishSpans().joinToString("") { it.text }
    fun finishSpans(): List<SpeechSpan> = correct(pending).also { pending = AttributedText("", IntArray(0)) }

    private fun correct(raw: AttributedText): List<SpeechSpan> {
        var text = raw
        for ((pattern, replacement) in compiled) text = text.replace(pattern, replacement)
        return text.trim().spans()
    }

    /**
     * Exact positional alignment through literal replacements, O(text + matches)
     * per rule. No transcript-wide edit-distance matrix or second recognition
     * pass is needed. The only persistent metadata is one integer per buffered
     * raw character, with exactly the same lifetime as Off's correction suffix.
     */
    private class AttributedText(val text: String, private val owners: IntArray) {
        fun slice(start: Int, end: Int) = AttributedText(text.substring(start, end), owners.copyOfRange(start, end))

        fun trim(startOnly: Boolean = false): AttributedText {
            val start = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
            val end = if (startOnly) text.length else (text.indexOfLast { !it.isWhitespace() } + 1).coerceAtLeast(start)
            return slice(start, end)
        }

        fun append(other: AttributedText): AttributedText {
            if (text.isEmpty()) return other.trim()
            val separator = if (other.text.firstOrNull()?.isWhitespace() == true) "" else " "
            val joined = text + separator + other.text
            val labels = IntArray(joined.length) { -1 }
            owners.copyInto(labels)
            other.owners.copyInto(labels, text.length + separator.length)
            return AttributedText(joined, labels)
        }

        fun replace(pattern: Regex, replacement: String): AttributedText {
            val matches = pattern.findAll(text).toList()
            if (matches.isEmpty()) return this
            val length = text.length + matches.sumOf { replacement.length - it.value.length }
            val output = StringBuilder(length)
            val labels = IntArray(length)
            var from = 0
            for (match in matches) {
                val start = match.range.first
                val stop = match.range.last + 1
                owners.copyInto(labels, output.length, from, start)
                output.append(text, from, start)
                // A phrase rewritten across two voices has no trustworthy
                // intra-replacement timing. Mark that replacement Unknown, not
                // whichever speaker happened to cause a premature buffer flush.
                var owner: Int? = null
                for (i in start until stop) if (text[i].isLetterOrDigit()) {
                    owner = if (owner == null || owner == owners[i]) owners[i] else -1
                }
                labels.fill(owner ?: -1, output.length, output.length + replacement.length)
                output.append(replacement)
                from = stop
            }
            owners.copyInto(labels, output.length, from, text.length)
            output.append(text, from, text.length)
            return AttributedText(output.toString(), labels)
        }

        fun spans(): List<SpeechSpan> {
            val result = mutableListOf<SpeechSpan>()
            var start = 0
            while (start < text.length) {
                var end = start + 1
                while (end < text.length && owners[end] == owners[start]) end++
                result += SpeechSpan(text.substring(start, end), owners[start])
                start = end
            }
            return result
        }

        companion object {
            fun fromSpans(spans: List<SpeechSpan>): AttributedText {
                val text = StringBuilder()
                val owners = ArrayList<Int>()
                for (span in spans) {
                    if (span.text.isBlank()) continue
                    if (text.isNotEmpty() && !text.last().isWhitespace() && !span.text.first().isWhitespace()) {
                        text.append(' ')
                        owners += span.speaker
                    }
                    text.append(span.text)
                    repeat(span.text.length) { owners += span.speaker }
                }
                return AttributedText(text.toString(), owners.toIntArray())
            }
        }
    }
}
