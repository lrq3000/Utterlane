package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.io.Closeable
import java.util.ArrayDeque

interface SpeakerProbabilityStream : Closeable {
    fun push(samples: ShortArray, final: Boolean): FloatArray
}

/** Per-recording state. ASR may re-read overlap; the speaker cache must never do so. */
class DiarizedWindowProcessor(
    private val backend: RecognitionBackend,
    private val stream: SpeakerProbabilityStream,
    private val count: Int,
    private val textOnly: Boolean = false,
    private val options: RuntimeOptions = RuntimeOptions()
) : Closeable {
    init { require(count in 0..8); options.requireValid() }

    // The pinned model schedules encoder frames at 80 ms. Account for the whole
    // batched chunk plus right context, not only nominal attention lookahead.
    private val nativeLagMs = when (options.diarizationMode) {
        "low_latency" -> (9 * options.diarizationBatch + 4) * 80
        "ultra_low_latency" -> (3 * options.diarizationBatch + 1) * 80
        else -> (6 * options.diarizationBatch + 2) * 80
    }
    private val paddingMs = maxOf(options.alignmentToleranceMs, options.unknownBridgeMs, options.speakerConfirmationMs)
    private val waitSamples = (nativeLagMs + options.labelLookaheadMs + paddingMs + 20) * 16L
    // Retain the pending word's entire (possibly untimed) window, its wait budget,
    // and one incoming IPC window. This is independent of recording duration.
    private val timeline = SpeakerTimeline(count, ((2 * 192000 + waitSamples + paddingMs * 16L + 319) / 160).toInt(), options)
    private val pending = ArrayDeque<WindowText.Word>()
    private var pendingCharacters = 0
    private var fed = 0L
    private var finished = false
    private var closed = false
    private var previous = -1

    fun process(window: AudioWindow): List<SpeechSpan> {
        check(!finished && !closed)
        val end = window.startSample + window.samples.size
        require(window.samples.size <= 192000 && window.startSample <= fed && fed <= end &&
            window.ownedStart >= window.startSample && window.ownedEnd <= end)
        // Match Off's ASR input: built-in models use overlap; custom models
        // decode disjoint ownership once. Their times are relative to this
        // sliced PCM, so its absolute origin must move with the slice too.
        val recognitionWindow = if (textOnly) window.copy(
            samples = window.samples.copyOfRange((window.ownedStart - window.startSample).toInt(),
                (window.ownedEnd - window.startSample).toInt()),
            startSample = window.ownedStart
        ) else window
        val result = backend.transcribeWindow(recognitionWindow.samples)
        val words = when {
            textOnly && result.text != null -> WindowText.alignRawText(result, recognitionWindow) ?: rawText(result, recognitionWindow)
            WindowText.hasTimings(result) -> WindowText.ownedWords(result.tokens, result.timestamps, recognitionWindow, result.ends)
            else -> rawText(result, recognitionWindow)
        }
        val output = SpanCollector()
        if (count != 1) {
            val keepFrom = minOf(pending.peekFirst()?.start ?: window.startSample, window.startSample) - paddingMs * 16L
            timeline.discardBefore(keepFrom.coerceAtLeast(0))
            val fresh = window.samples.copyOfRange((fed - window.startSample).toInt(), window.samples.size)
            timeline.append(stream.push(fresh, window.isFinal))
            // Only the documented centered-FFT tail may inherit the last frame.
            // Larger final shortfalls remain Unknown, while all text still flushes.
            if (window.isFinal && window.ownedEnd - timeline.endSample in 0..256) timeline.finishAt(window.ownedEnd)
        }
        fed = end
        finished = window.isFinal
        drain(output)
        val lastText = words.indexOfLast { it.text.isNotBlank() }
        val separator = StringBuilder()
        var firstText = true
        for ((index, original) in words.withIndex()) {
            if (index > lastText) break
            // Ownership has already been selected. Retain whitespace-only
            // groups as separators on the following owned text, so they cannot
            // disappear as blank spans or change a lexical group's ownership.
            if (original.text.isBlank()) { separator.append(original.text); continue }
            var text = separator.append(original.text).toString()
            separator.setLength(0)
            if (firstText) { text = text.trimStart(); firstText = false }
            if (index == lastText) text = text.trimEnd()
            val word = original.copy(text = text)
            // Bound pathological metadata density too, independently of duration.
            while (pending.isNotEmpty() && (pending.size >= 8192 || pendingCharacters + word.text.length > 262144)) {
                emit(pending.removeFirst(), output, forceUnknown = true)
            }
            pending.addLast(word)
            pendingCharacters += word.text.length
            if (pendingCharacters > 262144) emit(pending.removeFirst(), output, forceUnknown = true)
        }
        drain(output)
        return output.finish()
    }

    private fun rawText(result: WindowResult, window: AudioWindow): List<WindowText.Word> =
        result.text?.takeIf { it.isNotBlank() }?.let {
            listOf(WindowText.Word(it, window.startSample, window.startSample + window.samples.size, coarse = true))
        }.orEmpty()

    private fun drain(output: SpanCollector) {
        while (pending.isNotEmpty()) {
            val word = pending.peekFirst()
            val horizonReady = count == 1 || timeline.endSample >= word.end +
                maxOf(options.labelLookaheadMs, paddingMs) * 16L
            val labelReady = timeline.endSample >= word.end + options.labelLookaheadMs * 16L
            // Committed probabilities inside the word cannot be changed by
            // later input. Only undecided intervals need the longer horizon
            // for alignment, gap bridging, or initial-speech backfill.
            val directSpeaker = if (!finished && !horizonReady && labelReady) {
                if (word.text.none { it.isLetterOrDigit() }) previous
                else timeline.speakerDuring(word.start, word.end, previous, word.coarse, allowFallback = false)
            } else -1
            val ready = horizonReady || directSpeaker >= 0
            val expired = fed - word.end >= waitSamples
            if (!finished && !ready && !expired) break // Never wait for future input inside this call.
            emit(pending.removeFirst(), output, forceUnknown = !ready && !finished, directSpeaker = directSpeaker)
        }
    }

    private fun emit(word: WindowText.Word, output: SpanCollector, forceUnknown: Boolean = false, directSpeaker: Int = -1) {
        pendingCharacters -= word.text.length
        val speaker = when {
            count == 1 -> 0
            // Punctuation follows the last owned lexical speaker. Its ASR
            // timestamp still controls text ownership, not a new voice label.
            word.text.none { it.isLetterOrDigit() } -> previous
            forceUnknown || word.end > timeline.endSample -> -1
            directSpeaker >= 0 -> directSpeaker
            else -> timeline.speakerDuring(word.start, word.end, previous, word.coarse)
        }
        previous = speaker
        // Preserve internal ASR whitespace (dictionary rules can depend on it).
        // Only an original audio-window boundary is normalized above. A delayed
        // tail can start a callback mid-window: its leading separator still
        // belongs to the recognizer and may be part of a multi-word correction.
        output.append(word.text, speaker)
    }

    /** Linear accumulation even for dense timestamp arrays in a single callback. */
    private class SpanCollector {
        private val spans = mutableListOf<SpeechSpan>()
        private val text = StringBuilder()
        private var speaker = -1

        fun append(value: String, next: Int) {
            if (value.isBlank()) return
            if (next != speaker) flush()
            speaker = next
            if (text.isNotEmpty() && !text.last().isWhitespace() && !value.first().isWhitespace()) text.append(' ')
            text.append(value)
        }

        private fun flush() {
            if (text.isNotEmpty()) spans += SpeechSpan(text.toString(), speaker)
            text.setLength(0)
        }

        fun finish(): List<SpeechSpan> { flush(); return spans }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        pendingCharacters = 0
        stream.close()
    }
}
