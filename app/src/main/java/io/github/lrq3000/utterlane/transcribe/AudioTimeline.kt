package io.github.lrq3000.utterlane.transcribe

/** Hourly PCM parts (or one encoded file) form one O(1)-addressable timeline. */
class AudioTimeline(val durationMs: Long, private val partMs: Long = durationMs, private val prefixMs: Long = 0) {
    init { require(durationMs > 0 && partMs > 0 && (durationMs - 1) / partMs < Int.MAX_VALUE) }
    data class Position(val part: Int, val offsetMs: Long)
    fun locate(positionMs: Long): Position {
        val position = positionMs.coerceIn(0, durationMs)
        if (prefixMs > 0) {
            if (position < prefixMs || prefixMs >= durationMs) return Position(0, position)
            val part = minOf((position - prefixMs) / partMs, (durationMs - prefixMs - 1) / partMs).toInt() + 1
            return Position(part, position - start(part))
        }
        val part = minOf(position / partMs, (durationMs - 1) / partMs).toInt()
        return Position(part, position - start(part))
    }
    fun start(part: Int): Long = if (prefixMs > 0) { if (part == 0) 0 else prefixMs + (part - 1L) * partMs } else part.toLong() * partMs
    fun duration(part: Int): Long = if (part == 0 && prefixMs > 0) prefixMs else minOf(partMs, durationMs - start(part))
}
