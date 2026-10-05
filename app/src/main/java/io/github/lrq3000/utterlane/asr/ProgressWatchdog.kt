package io.github.lrq3000.utterlane.asr

/** A status packet is not evidence of computation, even when its sequence advances. */
internal data class RecognitionProgress(
    val requestId: Int,
    val sequence: Long,
    val stage: String,
    val completedUnits: Long,
    val completed: Boolean = false,
    val opaque: Boolean = true
)

/**
 * O(1), request-local watchdog. The caller supplies uptime (which excludes deep sleep)
 * and the interactive/device-idle gate. As with AwakeDeadline, neither transition
 * interval is charged: an unobserved part of sleep must never become a timeout.
 *
 * Both budgets begin at dispatch, including queue time. Queue/start/heartbeat messages
 * are observable but cannot extend either budget. Zero explicitly disables a limit.
 * This is a configurable fallback for opaque native calls, not a universal hang detector.
 */
internal class ProgressWatchdog(
    private val requestId: Int,
    private val stallMillis: Long,
    private val absoluteMillis: Long,
    now: Long,
    paused: Boolean
) {
    enum class Expiration { PROGRESS_STALL, ABSOLUTE_BUDGET, UNAVAILABLE_OPAQUE }

    init { require(stallMillis >= 0 && absoluteMillis >= 0) }
    private var previous = now
    private var wasPaused = paused
    private var elapsed = 0L
    private var lastProgress = 0L
    private var sequence = 0L
    private var units = 0L
    private var stage = "queued"
    private var opaque = true
    private var expired: Expiration? = null

    @Synchronized fun accept(packet: RecognitionProgress, now: Long, paused: Boolean): Boolean {
        if (packet.requestId != requestId || packet.sequence <= sequence || packet.stage.isBlank()) return false
        if (packet.completed && packet.completedUnits <= units) return false
        if (!packet.completed && packet.completedUnits != units) return false
        if (expiration(now, paused) != null) return false
        sequence = packet.sequence
        stage = packet.stage
        opaque = packet.opaque
        if (packet.completed) {
            units = packet.completedUnits
            lastProgress = elapsed
        }
        return true
    }

    @Synchronized fun expiration(now: Long, paused: Boolean): Expiration? {
        if (!paused && !wasPaused) elapsed += (now - previous).coerceAtLeast(0)
        previous = maxOf(previous, now)
        wasPaused = paused
        if (!paused && expired == null) expired = when {
            absoluteMillis > 0 && elapsed >= absoluteMillis -> Expiration.ABSOLUTE_BUDGET
            stallMillis > 0 && elapsed - lastProgress >= stallMillis ->
                if (opaque) Expiration.UNAVAILABLE_OPAQUE else Expiration.PROGRESS_STALL
            else -> null
        }
        return expired
    }

    @Synchronized fun activity() = RecognitionActivity(
        requestId, stage, elapsed, elapsed - lastProgress, units, active = true,
        message = if (opaque) "Computational progress unavailable for this stage; using configured fallback." else null
    )
}
