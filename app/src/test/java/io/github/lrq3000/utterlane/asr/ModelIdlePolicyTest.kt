package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class ModelIdlePolicyTest {
    private var now = 0L
    private val policy = ModelIdlePolicy { now }

    @Test fun persistedOptionsHaveExactDurationsAndSafeDefault() {
        assertEquals(listOf(0L, 300_000L, 1_200_000L, 3_600_000L, 10_800_000L, 86_400_000L, null),
            ModelIdleTimeout.entries.map { it.milliseconds })
        ModelIdleTimeout.entries.forEach { assertEquals(it, ModelIdleTimeout.fromKey(it.key)) }
        assertEquals(ModelIdleTimeout.TWENTY_MINUTES, ModelIdleTimeout.fromKey(null))
        assertEquals(ModelIdleTimeout.TWENTY_MINUTES, ModelIdleTimeout.fromKey("invalid"))
    }

    @Test fun defaultExpiresOnlyAtTwentyMinutesOfIdle() {
        assertNull(policy.remainingMillis())
        policy.setIdle(true)
        now = 1_199_999
        assertEquals(1L, policy.remainingMillis())
        now++
        assertEquals(0L, policy.remainingMillis())
        now++
        assertEquals(0L, policy.remainingMillis())
    }

    @Test fun repeatedIdleNotificationsDoNotExtendDeadline() {
        policy.setIdle(true)
        now = 300_000
        policy.setIdle(true)
        assertEquals(900_000L, policy.remainingMillis())
    }

    @Test fun activityProtectsTheModelAndStartsAFreshIdlePeriodAfterward() {
        policy.setIdle(true)
        now = 100_000
        policy.setIdle(false)
        now = 10_000_000
        assertNull(policy.remainingMillis())
        policy.setIdle(true)
        assertEquals(1_200_000L, policy.remainingMillis())
    }

    @Test fun immediateExpiresOnlyWhenIdle() {
        policy.timeout = ModelIdleTimeout.IMMEDIATE
        assertNull(policy.remainingMillis())
        policy.setIdle(true)
        assertEquals(0L, policy.remainingMillis())
        policy.setIdle(false)
        assertNull(policy.remainingMillis())
    }

    @Test fun policyChangesKeepElapsedIdleTimeIncludingNever() {
        policy.setIdle(true)
        now = 600_000
        policy.timeout = ModelIdleTimeout.HOUR
        assertEquals(3_000_000L, policy.remainingMillis())
        policy.timeout = ModelIdleTimeout.NEVER
        assertNull(policy.remainingMillis())
        policy.timeout = ModelIdleTimeout.FIVE_MINUTES
        assertEquals(0L, policy.remainingMillis())
    }

    @Test fun elapsedClockIncludesSleepAndLongDurationsDoNotOverflow() {
        policy.timeout = ModelIdleTimeout.DAY
        now = 5_000_000_000L
        policy.setIdle(true)
        now += 86_399_999L
        assertEquals(1L, policy.remainingMillis())
        now += 1_000_000L // Device slept past the deadline before the wake callback.
        assertEquals(0L, policy.remainingMillis())
    }
}
