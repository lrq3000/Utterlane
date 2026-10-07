package io.github.lrq3000.utterlane.history

import java.util.TreeSet

/** Shared audio/text policy. Creation dates never change when retention restarts. */
data class RetentionMark(val since: Long, val pinned: Boolean = false, val holdForLaunch: String? = null) {
    fun pin(value: Boolean, duration: HistoryRetention, now: Long, launch: String): RetentionMark =
        if (value) copy(pinned = true, holdForLaunch = null)
        else RetentionMark(now, holdForLaunch = if (duration == HistoryRetention.NONE) launch else null)
}

/**
 * Only eligible, unpinned metadata enters the ordered index. Looking for the next
 * due item does not scan audio, text, pinned items or the entire history. Owners
 * serialize mutations with their metadata transaction/deletion lock.
 */
internal class RetentionIndex {
    private data class Key(val id: String, val since: Long)
    private val ordered = TreeSet(compareBy<Key> { it.since }.thenBy { it.id })
    private val keys = mutableMapOf<String, Key>()
    private val held = mutableMapOf<String, String>()
    private var first: Key? = null

    fun put(id: String, mark: RetentionMark, eligible: Boolean = true) {
        remove(id)
        if (!eligible || mark.pinned) return
        if (mark.holdForLaunch != null) held[id] = mark.holdForLaunch
        else {
            val key = Key(id, mark.since)
            keys[id] = key
            ordered.add(key)
            first = ordered.firstOrNull()
        }
    }

    fun remove(id: String) {
        keys.remove(id)?.let { ordered.remove(it); first = ordered.firstOrNull() }
        held.remove(id)
    }

    fun firstDue(duration: HistoryRetention, now: Long): String? =
        first?.takeIf { duration.expired(it.since, now) }?.id

    fun releasedBy(launch: String): List<String> = held.filterValues { it != launch }.keys.toList()
}
