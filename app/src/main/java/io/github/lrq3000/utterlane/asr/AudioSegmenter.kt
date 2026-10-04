package io.github.lrq3000.utterlane.asr

import kotlin.math.abs

data class AudioWindow(
    val samples: ShortArray,
    val startSample: Long,
    val ownedStart: Long,
    val ownedEnd: Long
)

/** A constant-sized sliding window. Context is decoded again, but has no text ownership. */
class AudioSegmenter(
    private val sampleRate: Int = 16000,
    maxSeconds: Int = 10,
    contextSeconds: Double = 1.0,
    private val consume: suspend (AudioWindow) -> Unit
) {
    private val maximum = maxSeconds * sampleRate
    private val context = (contextSeconds * sampleRate).toInt()
    private val buffer = ShortArray(maximum + 2 * context + 1)
    private var size = 0
    private var start = 0L
    private var ownedStart = 0L
    private var boundary: Long? = null
    private var quietSamples = 0
    private var finished = false

    init { require(sampleRate > 0 && maximum > 0 && context >= 0) }

    suspend fun accept(samples: ShortArray) {
        check(!finished)
        for (sample in samples) {
            buffer[size++] = sample
            val end = start + size
            quietSamples = if (abs(sample.toInt()) < 200) quietSamples + 1 else 0
            if (boundary == null) {
                val length = end - ownedStart
                // Energy only chooses a cut; even quiet speech and silence are retained.
                if (length >= maximum || (length >= minOf(maximum, 3 * sampleRate) &&
                            quietSamples >= sampleRate * 6 / 10)) boundary = end
            }
            boundary?.let { if (end >= it + context) emit(it) }
        }
    }

    suspend fun finish() {
        if (finished) return
        boundary?.let { emit(it) }
        if (start + size > ownedStart) emit(start + size)
        // Commit completion only after consume succeeds, so interruption cannot
        // permanently hide the final buffered window from a resumed finish().
        finished = true
    }

    private suspend fun emit(end: Long) {
        val audioEnd = minOf(start + size, end + context)
        consume(AudioWindow(buffer.copyOfRange(0, (audioEnd - start).toInt()), start, ownedStart, end))
        val keepStart = maxOf(start, end - context)
        val drop = (keepStart - start).toInt()
        System.arraycopy(buffer, drop, buffer, 0, size - drop)
        size -= drop
        start = keepStart
        ownedStart = end
        boundary = null
        quietSamples = 0
    }
}

/** Group subword tokens before selecting ownership, so a cut never emits half a word. */
object WindowText {
    fun select(tokens: Array<String>, timestamps: FloatArray, window: AudioWindow, sampleRate: Int = 16000): String {
        require(tokens.size == timestamps.size) { "Recognizer did not return token timestamps" }
        val result = StringBuilder()
        val word = StringBuilder()
        var position = 0L
        fun flush() {
            if (position >= window.ownedStart && position < window.ownedEnd) result.append(word)
            word.setLength(0)
        }
        tokens.forEachIndexed { index, token ->
            if (token.firstOrNull()?.isWhitespace() == true && word.isNotEmpty()) flush()
            if (word.isEmpty()) position = window.startSample + (timestamps[index] * sampleRate).toLong()
            word.append(token)
        }
        flush()
        return result.toString().trim()
    }
}
