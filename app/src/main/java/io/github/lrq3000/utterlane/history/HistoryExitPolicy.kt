package io.github.lrq3000.utterlane.history

/** Automatic saving and expiration are independent. Disabling automatic history
 * does not delete existing saved entries; explicit pins override both choices. */
data class HistoryExitPolicy(val automatic: Boolean, val duration: HistoryRetention) {
    fun keeps(mark: RetentionMark, stored: Boolean, now: Long): Boolean =
        mark.pinned || mark.holdForLaunch != null ||
            ((stored || automatic) && !duration.expired(mark.since, now))
}
