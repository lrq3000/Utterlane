package io.github.lrq3000.utterlane.audio

enum class InputFallbackReason { DISCONNECTED, UNAVAILABLE, ROUTE_CHANGED }

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
    private var lastTargetFrames = startedAt
    private var lastFallbackFrames = startedAt
    private var reopened = false
    var state = CaptureInputState(connecting = !target.isPhone)
        private set
    val isFallback: Boolean get() = state.fallbackFrom != null
    val desiredKey: String get() = if (isFallback) AudioInput.PHONE_KEY else target.key
    val failed: Boolean get() = isFallback && clock() - lastFallbackFrames >= 5000

    fun observe(actual: AudioInput?, targetAvailable: Boolean, frames: Boolean, silenced: Boolean = false) {
        val now = clock()
        if (!target.isPhone && !isFallback) {
            when {
                !targetAvailable -> fallback(InputFallbackReason.DISCONNECTED)
                targetConfirmed && actual != null && actual.key != target.key -> fallback(InputFallbackReason.ROUTE_CHANGED)
                frames && !silenced && actual?.key == target.key -> { targetConfirmed = true; lastTargetFrames = now }
                !targetConfirmed && now - startedAt >= activationTimeoutMillis -> fallback(InputFallbackReason.UNAVAILABLE)
                targetConfirmed && now - lastTargetFrames >= 1500 -> fallback(InputFallbackReason.UNAVAILABLE)
            }
        }
        var receiving = state.receivingFallback
        if (isFallback) {
            if (actual?.isPhone == true && frames && !silenced) {
                lastFallbackFrames = now
                reopened = false
                receiving = true
            } else if (actual?.isPhone != true || silenced || now - lastFallbackFrames >= 1500) receiving = false
        }
        state = state.copy(actual = actual, connecting = !target.isPhone && !targetConfirmed && !isFallback,
            receivingFallback = receiving)
    }

    fun fallback(reason: InputFallbackReason) {
        if (target.isPhone || isFallback) return
        lastFallbackFrames = clock()
        state = state.copy(connecting = false, fallbackFrom = target, fallbackReason = reason, receivingFallback = false)
    }

    /** Bound the old route's drain before one worker-owned reopen; callbacks never reopen. */
    fun takeReopenRequest(): Boolean {
        if (!isFallback || reopened || clock() - lastFallbackFrames < 1500) return false
        reopened = true
        return true
    }

    fun recorderReopened() {
        // An already healthy fallback gets a fresh recovery budget after a later
        // sleep. A failed fallback's own reopen must not extend its deadline.
        if (state.receivingFallback) lastFallbackFrames = clock()
        state = state.copy(actual = null, receivingFallback = false)
    }
}
