package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Test

class HistoryScheduleTest {
    @Test fun scheduleMatchesEachFiniteRetentionWithoutPollingImmediateOrForever() {
        for (duration in HistoryRetention.entries) {
            if (duration == HistoryRetention.NONE || duration == HistoryRetention.FOREVER) assertNull(HistorySchedule.interval(duration))
            else assertEquals(duration.millis, HistorySchedule.interval(duration))
        }
    }
}
