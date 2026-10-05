package io.github.lrq3000.utterlane.history

enum class HistoryRetention(val key: String, val millis: Long) {
    NONE("none", 0), HOUR("1h", 3600000), SIX_HOURS("6h", 21600000),
    DAY("1d", 86400000), WEEK("7d", 604800000), MONTH("30d", 2592000000),
    THREE_MONTHS("90d", 7776000000), FOREVER("forever", Long.MAX_VALUE);

    fun expired(referenceMs: Long, nowMs: Long): Boolean =
        this != FOREVER && (this == NONE || (nowMs >= referenceMs && nowMs - referenceMs >= millis))

    companion object {
        val DEFAULT = HOUR

        // Enable a short recovery window only when no preference was saved.
        // Preserve explicit opt-outs and the conservative unknown-key fallback.
        fun fromKey(key: String?) = if (key == null) DEFAULT else entries.find { it.key == key } ?: NONE
    }
}
