package io.github.lrq3000.utterlane.asr

import android.util.Log
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    options: RuntimeOptions = RuntimeOptions(),
    private val finishSpeakers: (suspend () -> List<SpeechSpan>)? = null
) : java.io.Closeable {
    private val speakerText = if (decodeSpeakers != null) SpeakerText(corrections, speakerLabel) else null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val segmenter = AudioSegmenter(flushPendingOnFinish = decodeSpeakers != null, options = options) { window ->
        if (!mayContinue()) return@AudioSegmenter
        val started = System.nanoTime()
        if (decodeSpeakers != null) {
            val spans = decodeSpeakers.invoke(window)
            if (!mayContinue()) return@AudioSegmenter
            for (text in checkNotNull(speakerText).accept(spans)) emit(text)
        } else {
            val text = decode(window)
            if (!mayContinue()) return@AudioSegmenter
            emit(corrections.accept(text))
        }
        if (!mayContinue()) return@AudioSegmenter
        onProcessed(window.ownedEnd, (System.nanoTime() - started) / 1000000)
        if (!mayContinue()) return@AudioSegmenter
        Log.i("TranscriptionSession", "Segment ${window.ownedStart}..${window.ownedEnd}; input=${window.samples.size}; completed=${store.segments}")
    }
    private var finished = false
    val hasSpeakerFinalization: Boolean get() = speakerText != null && finishSpeakers != null
    suspend fun accept(samples: ShortArray) {
        check(!closed.get()) { "Transcription session is closed" }
        if (!mayContinue()) return
        segmenter.accept(samples)
        currentCoroutineContext().ensureActive()
    }
    suspend fun finish() = finish {}

    /** Report the real EOF boundary only after the last ASR window completes.
     * Keep the zero-argument overload for recording pipeline method references. */
    suspend fun finish(onFinalizing: (Boolean) -> Unit) {
        if (finished || closed.get()) return
        try {
            if (!mayContinue()) return
            segmenter.finish()
            if (!mayContinue()) return
            onFinalizing(hasSpeakerFinalization)
            if (!mayContinue()) return
            // Speaker lookahead can outlive the last ASR window (notably with
            // zero right context). Its text must reach the same correction
            // buffer before any pending dictionary phrase is finalized.
            if (speakerText != null && finishSpeakers != null) {
                val spans = finishSpeakers.invoke()
                if (!mayContinue()) return
                for (text in speakerText.accept(spans)) emit(text)
                if (!mayContinue()) return
            }
            emit(speakerText?.finish() ?: corrections.finish())
            if (!mayContinue()) return
            finished = true
        } finally { close() }
    }
    override fun close() { if (closed.compareAndSet(false, true)) onClosed() }

    // Closing does not itself cancel an in-flight decoder or callback. Check
    // both signals after suspension so a late result cannot enter corrections,
    // publish text, or trigger the deferred final flush after its owner closes.
    private suspend fun mayContinue(): Boolean {
        currentCoroutineContext().ensureActive()
        return !closed.get()
    }

    private suspend fun emit(text: String) {
        if (text.isBlank() || !mayContinue()) return
        store.append(text)
        if (!mayContinue()) return
        onSegment(text.trimEnd())
    }
}

/** Bounded trailing words are retained, so ordered corrections can span audio windows. */
class StreamingCorrections(rules: List<DictionaryManager.ReplacementRule>) {
    private val stages = rules.map { CorrectionStage(it) }
    private var hasInput = false
    private var lastWasWhitespace = false

    fun accept(text: String): String = acceptSpans(listOf(SpeechSpan(text.trim(), -1))).joinToString("") { it.text }

    /** Labels share the raw correction buffer; they never cause a flush or enter a regex. */
    fun acceptSpans(spans: List<SpeechSpan>): List<SpeechSpan> {
        var incoming = AttributedText.fromSpans(spans)
        if (incoming.text.isBlank()) return emptyList()
        if (hasInput && !lastWasWhitespace && !incoming.text.first().isWhitespace()) {
            incoming = AttributedText(" ", intArrayOf(-1)).append(incoming)
        }
        hasInput = true
        lastWasWhitespace = incoming.text.last().isWhitespace()
        for (stage in stages) incoming = stage.accept(incoming)
        return incoming.trim().spans()
    }
    fun finish(): String = finishSpans().joinToString("") { it.text }
    fun finishSpans(): List<SpeechSpan> {
        var tail = AttributedText("", IntArray(0))
        for (stage in stages) tail = stage.accept(tail).append(stage.finish())
        hasInput = false
        lastWasWhitespace = false
        return tail.trim().spans()
    }

    /**
     * Hold a suffix at each rule's input, not just at the raw ASR input. Otherwise
     * "a -> hello" then "hello b -> combined" can change wording when native
     * lookahead splits delivery after "a". This bounded pipeline preserves rule
     * order while making both labeled and Off output independent of batching.
     */
    private class CorrectionStage(private val rule: DictionaryManager.ReplacementRule) {
        private val pattern = Regex("\\b${Regex.escape(rule.from)}\\b",
            if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        private var pending = AttributedText("", IntArray(0))

        fun accept(incoming: AttributedText): AttributedText {
            pending = pending.append(incoming)
            val limit = (pending.text.length - rule.from.length - 1).coerceAtLeast(0)
            var cut = pending.text.lastIndexOf(' ', limit).coerceAtLeast(0)
            for (match in pattern.findAll(pending.text)) {
                if (match.range.first < cut && match.range.last >= cut) cut = match.range.first
            }
            val prefix = pending.slice(0, cut)
            // Keep separators through every stage: later rules may match them.
            pending = pending.slice(cut, pending.text.length)
            return prefix.replace(pattern, rule.to)
        }

        fun finish(): AttributedText = pending.replace(pattern, rule.to).also {
            pending = AttributedText("", IntArray(0))
        }
    }

    /**
     * Exact positional alignment through literal replacements, O(text + matches)
     * per rule. No transcript-wide edit-distance matrix or second recognition
     * pass is needed. The only persistent metadata is one integer per buffered
     * character in each rule's bounded suffix, shared by labeled and Off modes.
     */
    private class AttributedText(val text: String, private val owners: IntArray) {
        fun slice(start: Int, end: Int) = AttributedText(text.substring(start, end), owners.copyOfRange(start, end))

        fun trim(): AttributedText {
            val start = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
            val end = (text.indexOfLast { !it.isWhitespace() } + 1).coerceAtLeast(start)
            return slice(start, end)
        }

        fun append(other: AttributedText): AttributedText {
            if (text.isEmpty()) return other
            if (other.text.isEmpty()) return this
            val joined = text + other.text
            val labels = IntArray(joined.length) { -1 }
            owners.copyInto(labels)
            other.owners.copyInto(labels, text.length)
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
