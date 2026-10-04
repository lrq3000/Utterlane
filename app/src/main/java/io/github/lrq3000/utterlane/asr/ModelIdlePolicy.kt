package io.github.lrq3000.utterlane.asr

/** Stable keys keep stored preferences independent of translated labels and enum order. */
enum class ModelIdleTimeout(val key: String, val milliseconds: Long?) {
    IMMEDIATE("immediate", 0L),
    FIVE_MINUTES("5_minutes", 5 * 60_000L),
    TWENTY_MINUTES("20_minutes", 20 * 60_000L),
    HOUR("1_hour", 60 * 60_000L),
    THREE_HOURS("3_hours", 3 * 60 * 60_000L),
    DAY("24_hours", 24 * 60 * 60_000L),
    NEVER("never", null);

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String?): ModelIdleTimeout = byKey[key] ?: TWENTY_MINUTES
    }
}

/**
 * Deadline calculation only; the owner serializes access with its model state.
 * Use elapsed realtime (including sleep), not wall time or coroutine delay time.
 */
class ModelIdlePolicy(private val elapsedMillis: () -> Long) {
    var timeout = ModelIdleTimeout.TWENTY_MINUTES
    private var idleSince: Long? = null

    fun setIdle(idle: Boolean) {
        if (!idle) idleSince = null
        else if (idleSince == null) idleSince = elapsedMillis()
    }

    fun remainingMillis(): Long? {
        val since = idleSince ?: return null
        val duration = timeout.milliseconds ?: return null
        return (duration - (elapsedMillis() - since).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
