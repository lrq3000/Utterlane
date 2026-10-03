package com.translander.history

enum class HistoryRetention(val key: String, val millis: Long) {
    NONE("none", 0), HOUR("1h", 3600000), SIX_HOURS("6h", 21600000),
    DAY("1d", 86400000), WEEK("7d", 604800000), MONTH("30d", 2592000000),
    THREE_MONTHS("90d", 7776000000), FOREVER("forever", Long.MAX_VALUE);

    fun expired(referenceMs: Long, nowMs: Long): Boolean =
        this != FOREVER && (this == NONE || (nowMs >= referenceMs && nowMs - referenceMs >= millis))

    companion object { fun fromKey(key: String?) = entries.find { it.key == key } ?: NONE }
}
