package io.github.lrq3000.utterlane.history

import java.util.TreeMap

/** Creation time, not retention/unpin time, owns the stable newest-first order. */
data class HistoryCursor(val created: Long, val id: String) : Comparable<HistoryCursor> {
    override fun compareTo(other: HistoryCursor): Int =
        other.created.compareTo(created).takeIf { it != 0 } ?: id.compareTo(other.id)
}

enum class HistoryDirection { REFRESH, APPEND, PREPEND }
data class HistoryPage<T>(val entries: List<T>, val before: HistoryCursor?, val after: HistoryCursor?)

/** Visible metadata only; repositories own synchronization and payload lifetimes.
 * Keyset lookup avoids rescanning every earlier record on later pages. A cursor
 * remains meaningful even when its entry was deleted between two loads. */
internal class HistoryIndex<T> {
    private val ordered = TreeMap<HistoryCursor, T>()
    fun put(key: HistoryCursor, entry: T) { ordered[key] = entry }
    fun remove(key: HistoryCursor) { ordered.remove(key) }

    /** Compatibility for bounded legacy callers; continuous browsing uses page(). */
    fun list(page: Int, pageSize: Int): List<T> {
        require(page >= 0 && pageSize > 0)
        val offset = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return ordered.values.asSequence().drop(offset).take(pageSize).toList()
    }

    fun page(anchor: HistoryCursor? = null, direction: HistoryDirection = HistoryDirection.REFRESH,
        limit: Int = 30): HistoryPage<T> {
        require(limit > 0)
        val entries = when {
            anchor == null -> ordered.entries.take(limit)
            direction == HistoryDirection.APPEND -> ordered.tailMap(anchor, false).entries.take(limit)
            direction == HistoryDirection.PREPEND -> ordered.headMap(anchor, false).descendingMap().entries.take(limit).asReversed()
            else -> {
                val newer = ordered.headMap(anchor, false).descendingMap()
                var before = newer.entries.take(limit / 2)
                val after = ordered.tailMap(anchor, true).entries.take(limit - before.size)
                // Near the oldest boundary, fill from newer entries instead of
                // returning half a window and moving the visible item to the top.
                if (before.size + after.size < limit) before = newer.entries.take(limit - after.size)
                before.asReversed() + after
            }
        }
        val first = entries.firstOrNull()?.key
        val last = entries.lastOrNull()?.key
        return HistoryPage(entries.map { it.value },
            first?.takeIf { ordered.lowerKey(it) != null },
            last?.takeIf { ordered.higherKey(it) != null })
    }
}
