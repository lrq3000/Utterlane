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
    @Suppress("UNUSED_PARAMETER") textOnly: Boolean = false,
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
        // Exactly one recognition pass on the original PCM/context in every mode.
        // Generic models without trustworthy times keep their complete raw text.
        val result = backend.transcribeWindow(window.samples)
        val timed = result.tokens.isNotEmpty() && result.tokens.size == result.timestamps.size &&
            result.timestamps.all { it.isFinite() && it >= 0f } &&
            result.timestamps.asList().zipWithNext().all { (a, b) -> a <= b }
        val words = if (timed) WindowText.ownedWords(result.tokens, result.timestamps, window, result.ends)
            else result.text?.takeIf { it.isNotBlank() }?.let {
                listOf(WindowText.Word(it, window.ownedStart, window.ownedEnd, coarse = true))
            }.orEmpty()
        val output = mutableListOf<SpeechSpan>()
        if (count != 1) {
            val keepFrom = minOf(pending.peekFirst()?.start ?: window.ownedStart, window.ownedStart) - paddingMs * 16L
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
        for ((index, original) in words.withIndex()) {
            val word = if (index == 0) original.copy(text = original.text.trimStart()) else original
            // Bound pathological metadata density too, independently of duration.
            while (pending.isNotEmpty() && (pending.size >= 8192 || pendingCharacters + word.text.length > 262144)) {
                emit(pending.removeFirst(), output, forceUnknown = true)
            }
            pending.addLast(word)
            pendingCharacters += word.text.length
        }
        drain(output)
        return output
    }

    private fun drain(output: MutableList<SpeechSpan>) {
        while (pending.isNotEmpty()) {
            val word = pending.peekFirst()
            val ready = count == 1 || timeline.endSample >= word.end +
                maxOf(options.labelLookaheadMs, paddingMs) * 16L
            val expired = fed - word.end >= waitSamples
            if (!finished && !ready && !expired) break // Never wait for future input inside this call.
            emit(pending.removeFirst(), output, forceUnknown = !ready && !finished)
        }
    }

    private fun emit(word: WindowText.Word, output: MutableList<SpeechSpan>, forceUnknown: Boolean = false) {
        pendingCharacters -= word.text.length
        val speaker = when {
            count == 1 -> 0
            forceUnknown || word.end > timeline.endSample -> -1
            else -> timeline.speakerDuring(word.start, word.end, previous, word.coarse)
        }
        previous = speaker
        if (word.text.isBlank()) return
        val last = output.lastOrNull()
        // Preserve internal ASR whitespace (dictionary rules can depend on it).
        // Only an original audio-window boundary is normalized above. A delayed
        // tail can start a callback mid-window: its leading separator still
        // belongs to the recognizer and may be part of a multi-word correction.
        val text = word.text.trimEnd()
        if (last?.speaker == speaker) output[output.lastIndex] = last.copy(text = last.text +
            (if (text.firstOrNull()?.isWhitespace() == true) "" else " ") + text)
        else output += SpeechSpan(text, speaker)
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        pendingCharacters = 0
        stream.close()
    }
}
