package io.github.lrq3000.utterlane.audio

enum class InputFallbackReason { DISCONNECTED, UNAVAILABLE, ROUTE_CHANGED, UNSUPPORTED_ROUTE, MODE_NOT_APPLIED }

data class CaptureInputState(
    val actual: AudioInput? = null,
    val connecting: Boolean = false,
    val fallbackFrom: AudioInput? = null,
    val fallbackReason: InputFallbackReason? = null,
    val receivingFallback: Boolean = false
)

/** Capture-worker-confined policy; the session's target never follows settings edits. */
class CaptureRoutePolicy(private val target: AudioInput, private val clock: () -> Long,
    private val activationTimeoutMillis: Long) {
    constructor(target: AudioInput, clock: () -> Long) : this(target, clock, 5000)
    private val startedAt = clock()
    private var targetConfirmed = false
    private var targetSeen = false
    private var lastTargetFrames = startedAt
    private var lastFallbackFrames = startedAt
    private var reopened = false
    private var phoneVerificationStartedAt = startedAt
    private var phoneSelectionFailed = false
    private var verificationStartedAt: Long? = null
    private var verificationTimeoutMillis = 1500L
    var state = CaptureInputState(connecting = !target.isPhone)
        private set
    val isFallback: Boolean get() = state.fallbackFrom != null
    val desiredKey: String get() = if (isFallback) AudioInput.PHONE_KEY else target.key
    val failed: Boolean get() = phoneSelectionFailed ||
        (isFallback && (clock() - lastFallbackFrames >= 5000 || verificationExpired(clock())))

    fun observe(actual: AudioInput?, targetAvailable: Boolean, frames: Boolean, silenced: Boolean = false,
        verified: Boolean = true) {
        val now = clock()
        val matchingFrames = frames && !silenced && actual?.key == desiredKey
        if (matchingFrames) {
            if (verified) verificationStartedAt = null
            else if (verificationStartedAt == null) verificationStartedAt = now
        }
        if (target.isPhone) {
            // There is no other fallback for an explicit Phone selection. Refuse
            // a positively different input, and bound missing route evidence.
            if (actual != null && !actual.isPhone) phoneSelectionFailed = true
            if (actual?.isPhone == true) phoneVerificationStartedAt = now
            else if (now - phoneVerificationStartedAt >= activationTimeoutMillis) phoneSelectionFailed = true
            if (verificationExpired(now)) phoneSelectionFailed = true
        }
        if (!target.isPhone && !isFallback) {
            if (matchingFrames) {
                // Matching PCM is live even while its bounded client prefix is
                // unverified. Do not confuse buffer warm-up with stalled capture.
                targetSeen = true
                lastTargetFrames = now
                if (verified) targetConfirmed = true
            }
            when {
                !targetAvailable -> fallback(InputFallbackReason.DISCONNECTED)
                targetSeen && actual != null && actual.key != target.key -> fallback(InputFallbackReason.ROUTE_CHANGED)
                verificationExpired(now) -> fallback(InputFallbackReason.UNAVAILABLE)
                !targetSeen && now - startedAt >= activationTimeoutMillis -> fallback(InputFallbackReason.UNAVAILABLE)
                targetSeen && now - lastTargetFrames >= 1500 -> fallback(InputFallbackReason.UNAVAILABLE)
            }
        }
        var receiving = state.receivingFallback
        if (isFallback) {
            if (actual?.isPhone == true && frames && !silenced) {
                lastFallbackFrames = now
                receiving = verified
                if (verified) { reopened = false; verificationStartedAt = null }
                else if (verificationStartedAt == null) verificationStartedAt = now
            } else if (!verified || actual?.isPhone != true || silenced || now - lastFallbackFrames >= 1500) receiving = false
        }
        state = state.copy(actual = actual, connecting = !target.isPhone && !targetConfirmed && !isFallback,
            receivingFallback = receiving)
    }

    fun fallback(reason: InputFallbackReason) {
        if (target.isPhone || isFallback) return
        lastFallbackFrames = clock()
        verificationStartedAt = null
        state = state.copy(connecting = false, fallbackFrom = target, fallbackReason = reason, receivingFallback = false)
    }

    /** Bound the old route's drain before one worker-owned reopen; callbacks never reopen. */
    fun takeReopenRequest(): Boolean {
        if (!isFallback || reopened || clock() - lastFallbackFrames < 1500) return false
        reopened = true
        return true
    }

    fun recorderReopened(verificationDrainMillis: Long = 0) {
        // The actual buffer can exceed the 1.5s stall/5s fallback budgets. Allow
        // one capacity's audio time plus scheduling grace, while a separate fixed
        // deadline prevents endlessly changing epochs from postponing failure.
        verificationTimeoutMillis = verificationDrainMillis.coerceAtLeast(0) + 1500
        verificationStartedAt = null
        phoneVerificationStartedAt = clock()
        // An already healthy fallback gets a fresh recovery budget after a later
        // sleep. A failed fallback's own reopen must not extend its deadline.
        if (state.receivingFallback) lastFallbackFrames = clock()
        state = state.copy(actual = null, receivingFallback = false)
    }

    private fun verificationExpired(now: Long): Boolean =
        verificationStartedAt?.let { now - it >= verificationTimeoutMillis } == true
}
