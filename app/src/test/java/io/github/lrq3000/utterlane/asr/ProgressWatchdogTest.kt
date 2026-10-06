package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class ProgressWatchdogTest {
    private fun watchdog(stall: Long = 30_000, absolute: Long = 0) =
        ProgressWatchdog(7, stall, absolute, 0, false)

    private fun progress(sequence: Long, units: Long = sequence, stage: String = "encoder", id: Int = 7) =
        RecognitionProgress(id, sequence, stage, units, completed = true, opaque = false)

    @Test fun slowComputationalProgressSurvivesFarBeyondNinetySeconds() {
        val watchdog = watchdog()
        for (step in 1L..40L) {
            assertTrue(watchdog.accept(progress(step), step * 20_000, false))
            assertNull(watchdog.expiration(step * 20_000 + 1, false))
        }
        assertEquals(800_001, watchdog.activity().elapsedMillis)
        assertEquals(40, watchdog.activity().completedUnits)
    }

    @Test fun duplicateBackwardAndUnchangedCountersNeverRenew() {
        val watchdog = watchdog()
        assertTrue(watchdog.accept(progress(3, 10), 10_000, false))
        assertFalse(watchdog.accept(progress(3, 11), 20_000, false))
        assertFalse(watchdog.accept(progress(2, 12), 25_000, false))
        assertFalse(watchdog.accept(progress(4, 10), 30_000, false))
        assertFalse(watchdog.accept(progress(5, 9), 35_000, false))
        assertEquals(ProgressWatchdog.Expiration.PROGRESS_STALL, watchdog.expiration(40_000, false))
    }

    @Test fun queueExecutingHeartbeatAndStageStartsAreNotProgress() {
        val watchdog = watchdog()
        listOf("queued", "executing", "heartbeat", "encoder_start").forEachIndexed { index, stage ->
            assertTrue(watchdog.accept(RecognitionProgress(7, index + 1L, stage, 0), index * 9_000L, false))
        }
        assertEquals(0, watchdog.activity().completedUnits)
        assertEquals(ProgressWatchdog.Expiration.UNAVAILABLE_OPAQUE, watchdog.expiration(30_000, false))
    }

    @Test fun completedLoadAndWarmupAreRealProgressButDoNotPromiseInternalVisibility() {
        val watchdog = watchdog()
        assertTrue(watchdog.accept(RecognitionProgress(7, 1, "model_load_complete", 1, completed = true), 29_000, false))
        assertNull(watchdog.expiration(40_000, false))
        assertTrue(watchdog.accept(RecognitionProgress(7, 2, "warmup_complete", 2, completed = true), 58_000, false))
        assertEquals(ProgressWatchdog.Expiration.UNAVAILABLE_OPAQUE, watchdog.expiration(88_000, false))
    }

    @Test fun absoluteBudgetExpiresDespiteProgress() {
        val watchdog = watchdog(30_000, 90_000)
        for (step in 1L..4L) assertTrue(watchdog.accept(progress(step), step * 20_000, false))
        assertEquals(ProgressWatchdog.Expiration.ABSOLUTE_BUDGET, watchdog.expiration(90_000, false))
        assertFalse(watchdog.accept(progress(5), 90_001, false))
    }

    @Test fun zeroExplicitlyDisablesEachLimit() {
        assertNull(watchdog(0, 0).expiration(1_000_000_000, false))
        assertEquals(ProgressWatchdog.Expiration.ABSOLUTE_BUDGET, watchdog(0, 1000).expiration(1000, false))
        assertEquals(ProgressWatchdog.Expiration.UNAVAILABLE_OPAQUE, watchdog(1000, 0).expiration(1000, false))
    }

    @Test fun sleepAndItsTransitionIntervalsNeverSpendEitherBudget() {
        val watchdog = watchdog(1000, 2000)
        assertNull(watchdog.expiration(500, false))
        assertNull(watchdog.expiration(600, true))
        assertNull(watchdog.expiration(600_000, true))
        assertTrue(watchdog.accept(progress(1), 600_050, true))
        assertNull(watchdog.expiration(600_100, false))
        assertNull(watchdog.expiration(601_099, false))
        assertEquals(1499, watchdog.activity().elapsedMillis)
        assertEquals(ProgressWatchdog.Expiration.PROGRESS_STALL, watchdog.expiration(601_100, false))
    }

    @Test fun anotherRequestCannotRenewOrReplaceStatus() {
        val watchdog = watchdog()
        assertFalse(watchdog.accept(progress(100, id = 8), 29_000, false))
        assertEquals("queued", watchdog.activity().stage)
        assertEquals(ProgressWatchdog.Expiration.UNAVAILABLE_OPAQUE, watchdog.expiration(30_000, false))
    }

    @Test fun backwardClockCannotGiveTimeBack() {
        val watchdog = watchdog(1000)
        assertNull(watchdog.expiration(500, false))
        assertNull(watchdog.expiration(100, false))
        assertEquals(ProgressWatchdog.Expiration.UNAVAILABLE_OPAQUE, watchdog.expiration(1000, false))
    }
}
