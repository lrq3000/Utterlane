package io.github.lrq3000.utterlane.asr

import kotlin.math.abs
import io.github.lrq3000.utterlane.settings.RuntimeOptions

data class AudioWindow(
    val samples: ShortArray,
    val startSample: Long,
    val ownedStart: Long,
    val ownedEnd: Long,
    val isFinal: Boolean = false
)

/** A constant-sized sliding window. Context is decoded again, but has no text ownership. */
class AudioSegmenter(
    private val sampleRate: Int = 16000,
    maxSeconds: Int = 10,
    contextSeconds: Double = 1.0,
    private val flushPendingOnFinish: Boolean = false,
    options: RuntimeOptions? = null,
    private val consume: suspend (AudioWindow) -> Unit
) {
    constructor(sampleRate: Int, maxSeconds: Int, contextSeconds: Double, flushPendingOnFinish: Boolean,
                consume: suspend (AudioWindow) -> Unit) :
        this(sampleRate, maxSeconds, contextSeconds, flushPendingOnFinish, null, consume)

    init {
        require(sampleRate > 0 && maxSeconds > 0 && contextSeconds.isFinite() && contextSeconds >= 0)
        options?.requireValid()
        val seconds = options?.let { it.asrWindowSeconds + it.asrLeftContextSeconds + it.asrRightContextSeconds }
            ?: (maxSeconds + 2 * contextSeconds)
        require(seconds * sampleRate <= 192000) { "ASR windows must fit the fixed 192000-sample IPC ceiling" }
    }
    private val maximum = ((options?.asrWindowSeconds ?: maxSeconds.toDouble()) * sampleRate).toInt().coerceAtLeast(1)
    private val minimum = ((options?.asrMinSeconds ?: minOf(maxSeconds, 3).toDouble()) * sampleRate).toInt().coerceAtLeast(1)
    private val leftContext = ((options?.asrLeftContextSeconds ?: contextSeconds) * sampleRate).toInt()
    private val rightContext = ((options?.asrRightContextSeconds ?: contextSeconds) * sampleRate).toInt()
    private val silence = ((options?.silenceDurationMs ?: 600) * sampleRate.toLong() / 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    private val amplitude = options?.silenceAmplitude ?: 200
    private val buffer = ShortArray(maximum + leftContext + rightContext + 1)
    private var size = 0
    private var start = 0L
    private var ownedStart = 0L
    private var boundary: Long? = null
    private var quietSamples = 0
    private var finished = false

    suspend fun accept(samples: ShortArray) {
        check(!finished)
        for (sample in samples) {
            buffer[size++] = sample
            val end = start + size
            considerBoundary(sample, end)
            // With no right context, hold one sample of scheduling lookahead so
            // EOF at an exact boundary still sends a final native flush. The
            // extra sample is not included in the preceding recognition window.
            val deliveryContext = if (flushPendingOnFinish && rightContext == 0) 1 else rightContext
            while (boundary != null && end >= checkNotNull(boundary) + deliveryContext) emit(checkNotNull(boundary))
        }
    }

    suspend fun finish() {
        if (finished) return
        // At EOF a pending boundary lacks its configured right context. Diarization must
        // flush before owning those samples; coalesce the remaining <=12 s into
        // one final window. The ordinary unlabeled path keeps its prior cuts.
        if (!flushPendingOnFinish) while (boundary != null) emit(checkNotNull(boundary))
        if (start + size > ownedStart) emit(start + size, final = true)
        // Commit completion only after consume succeeds, so interruption cannot
        // permanently hide the final buffered window from a resumed finish().
        finished = true
    }

    private suspend fun emit(end: Long, final: Boolean = false) {
        val audioEnd = minOf(start + size, end + rightContext)
        consume(AudioWindow(buffer.copyOfRange(0, (audioEnd - start).toInt()), start, ownedStart, end, final))
        val keepStart = maxOf(start, end - leftContext)
        val drop = (keepStart - start).toInt()
        System.arraycopy(buffer, drop, buffer, 0, size - drop)
        size -= drop
        start = keepStart
        ownedStart = end
        boundary = null
        quietSamples = 0
        // Right context may itself contain several short ownership windows.
        // Reconsider only retained future PCM, so independently configured long
        // right context cannot overrun the fixed buffer or skip ownership cuts.
        if (!final) for (index in (ownedStart - start).toInt() until size) {
            considerBoundary(buffer[index], start + index + 1)
            if (boundary != null) break
        }
    }

    private fun considerBoundary(sample: Short, end: Long) {
        if (boundary != null) return
        quietSamples = if (abs(sample.toInt()) < amplitude) (quietSamples.toLong() + 1).coerceAtMost(silence.toLong()).toInt() else 0
        val length = end - ownedStart
        // Energy only chooses a cut; even quiet speech and silence are retained.
        if (length >= maximum || (length >= minimum && quietSamples >= silence)) boundary = end
    }
}

/** Group subword tokens before selecting ownership, so a cut never emits half a word. */
object WindowText {
    data class Word(val text: String, val start: Long, val end: Long, val coarse: Boolean = false)

    fun hasTimings(result: WindowResult): Boolean = result.tokens.isNotEmpty() &&
        result.tokens.size == result.timestamps.size && result.timestamps.indices.all { i ->
            result.timestamps[i].isFinite() && result.timestamps[i] >= 0f &&
                (i == 0 || result.timestamps[i - 1] <= result.timestamps[i])
        }

    /**
     * Generic backends may return whole words without leading BPE whitespace.
     * Locate them monotonically in the authoritative raw result, preserving all
     * separators/punctuation. Missing lexical text or unusable timestamps makes
     * the entire alignment unavailable, never a license to discard ASR words.
     */
    fun alignRawText(result: WindowResult, window: AudioWindow): List<Word>? {
        val text = result.text ?: return null
        if (!hasTimings(result) || result.timestamps.any { it * 16000 >= window.samples.size }) return null
        val aligned = Array(result.tokens.size) { "" }
        var cursor = 0
        for ((index, token) in result.tokens.withIndex()) {
            val lexical = token.trim()
            if (lexical.isEmpty()) return null
            val at = text.indexOf(lexical, cursor)
            if (at < 0) return null
            val gap = text.substring(cursor, at)
            if (gap.any { it.isLetterOrDigit() }) return null
            if (index > 0 && lexical.any { it.isLetterOrDigit() }) {
                val punctuationEnd = gap.indexOfLast { !it.isWhitespace() } + 1
                aligned[index - 1] += gap.take(punctuationEnd)
                aligned[index] = gap.drop(punctuationEnd) + lexical
            } else aligned[index] = gap + lexical
            cursor = at + lexical.length
        }
        val suffix = text.substring(cursor)
        if (suffix.any { it.isLetterOrDigit() }) return null
        aligned[aligned.lastIndex] += suffix
        // The existing generic Off contract emits full result.text, including
        // context. Timing adds labels only; changing text ownership is separate.
        return ownedWords(aligned, result.timestamps,
            window.copy(ownedStart = window.startSample, ownedEnd = window.startSample + window.samples.size), result.ends)
    }

    fun select(tokens: Array<String>, timestamps: FloatArray, window: AudioWindow, sampleRate: Int = 16000): String {
        val result = StringBuilder()
        forEachOwnedWord(tokens, timestamps, window, sampleRate) { text, _ -> result.append(text) }
        return result.toString().trim()
    }

    /** Share word grouping between labeled and ordinary output so ownership cannot drift. */
    fun forEachOwnedWord(tokens: Array<String>, timestamps: FloatArray, window: AudioWindow, sampleRate: Int = 16000,
                         consume: (String, Long) -> Unit) {
        for (word in ownedWords(tokens, timestamps, window, sampleRate = sampleRate)) consume(word.text, word.start)
    }

    /** Native ends are optional. Inference is capped at 400 ms, never stretched over a silence. */
    fun ownedWords(tokens: Array<String>, timestamps: FloatArray, window: AudioWindow,
                   ends: FloatArray = floatArrayOf(), sampleRate: Int = 16000): List<Word> {
        require(tokens.size == timestamps.size) { "Recognizer did not return token timestamps" }
        val words = mutableListOf<Word>()
        val word = StringBuilder()
        var position = 0L
        var wordEnd = 0L
        fun flush() {
            if (word.isNotEmpty()) words += Word(word.toString(), position, wordEnd)
            word.setLength(0)
        }
        tokens.forEachIndexed { index, token ->
            // Standalone punctuation belongs to its lexical predecessor, even if
            // its timestamp lies in silence or across an ownership boundary.
            val lexical = token.any { it.isLetterOrDigit() }
            if (token.firstOrNull()?.isWhitespace() == true && lexical && word.isNotEmpty()) flush()
            if (word.isEmpty()) position = window.startSample + (timestamps[index] * sampleRate).toLong()
            if (lexical) wordEnd = ends.getOrNull(index)?.takeIf { it.isFinite() && it > timestamps[index] }
                ?.let { window.startSample + (it * sampleRate).toLong() } ?: 0L
            word.append(token)
        }
        flush()
        val audioEnd = window.startSample + window.samples.size
        return words.mapIndexedNotNull { index, item ->
            if (item.start < window.ownedStart || item.start >= window.ownedEnd) null else {
                val next = words.getOrNull(index + 1)?.start ?: audioEnd
                val inferred = minOf(next, item.start + sampleRate * 4 / 10)
                item.copy(end = (if (item.end > item.start) item.end else inferred)
                    .coerceIn(item.start, audioEnd))
            }
        }
    }
}
