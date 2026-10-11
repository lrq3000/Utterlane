package io.github.lrq3000.utterlane.history

/** Automatic saving and expiration are independent. Disabling automatic history
 * does not delete existing saved entries; explicit pins override both choices. */
data class HistoryExitPolicy(val automatic: Boolean, val duration: HistoryRetention) {
    fun requiresConfirmation(entry: HistoryEntry, now: Long): Boolean =
        !entry.completedTransientImport && !keeps(entry, now)

    fun keeps(entry: HistoryEntry, now: Long): Boolean {
        // A snapshotted transcript-only choice must not become an automatic save
        // merely because history is enabled when the user later navigates away.
        val effective = if (entry.temporary && entry.importRetention == ImportRetentionChoice.DISCARD)
            copy(automatic = false) else this
        return effective.keeps(entry.retention, stored = !entry.temporary, now)
    }

    fun keeps(mark: RetentionMark, stored: Boolean, now: Long): Boolean =
        mark.pinned || mark.holdForLaunch != null ||
            ((stored || automatic) && !duration.expired(mark.since, now))
}
