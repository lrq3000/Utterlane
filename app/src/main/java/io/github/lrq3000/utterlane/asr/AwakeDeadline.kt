package io.github.lrq3000.utterlane.asr

/** Finite active-time budget: time spent suspended cannot kill an in-flight window. */
internal class AwakeDeadline(private var remaining: Long, now: Long, paused: Boolean) {
    private var previous = now
    private var wasPaused = paused
    fun expired(now: Long, paused: Boolean): Boolean {
        if (!paused && !wasPaused) remaining -= (now - previous).coerceAtLeast(0)
        previous = now
        wasPaused = paused
        return !paused && remaining <= 0
    }
}
