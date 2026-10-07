package io.github.lrq3000.utterlane.transcribe

/** Hourly PCM parts (or one encoded file) form one O(1)-addressable timeline. */
class AudioTimeline(val durationMs: Long, private val partMs: Long = durationMs) {
    init { require(durationMs > 0 && partMs > 0 && (durationMs - 1) / partMs < Int.MAX_VALUE) }
    data class Position(val part: Int, val offsetMs: Long)
    fun locate(positionMs: Long): Position {
        val position = positionMs.coerceIn(0, durationMs)
        val part = minOf(position / partMs, (durationMs - 1) / partMs).toInt()
        return Position(part, position - start(part))
    }
    fun start(part: Int): Long = part.toLong() * partMs
    fun duration(part: Int): Long = minOf(partMs, durationMs - start(part))
}
