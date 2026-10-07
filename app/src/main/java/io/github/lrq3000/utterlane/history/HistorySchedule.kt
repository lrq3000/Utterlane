package io.github.lrq3000.utterlane.history

/** No polling loop for Immediate or Forever; finite schedules obey Android's floor. */
object HistorySchedule {
    fun interval(duration: HistoryRetention): Long? = when (duration) {
        HistoryRetention.NONE, HistoryRetention.FOREVER -> null
        else -> duration.millis.coerceAtLeast(15 * 60 * 1000L)
    }
}
