package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Test

class HistoryIndexTest {
    @Test fun equalDatesHaveStableIdOrderAndTimestampComparisonDoesNotOverflow() {
        val index = HistoryIndex<String>()
        listOf("c", "a", "b").forEach { index.put(HistoryCursor(10, it), it) }
        index.put(HistoryCursor(Long.MIN_VALUE, "oldest"), "oldest")
        index.put(HistoryCursor(Long.MAX_VALUE, "newest"), "newest")
        assertEquals(listOf("newest", "a", "b", "c", "oldest"), index.page().entries)
    }

    @Test fun forwardAndBackwardPagesHaveNoGapsOrDuplicates() {
        val index = populated(103)
        val visited = mutableListOf<Int>()
        var page = index.page(limit = 30)
        assertNull(page.before)
        while (true) {
            visited.addAll(page.entries)
            val next = page.after ?: break
            page = index.page(next, HistoryDirection.APPEND, 30)
        }
        assertEquals((0..102).toList(), visited)
        assertEquals((90..102).toList(), page.entries)
        val previous = index.page(page.before, HistoryDirection.PREPEND, 30)
        assertEquals((60..89).toList(), previous.entries)
        assertEquals((90..102).toList(), index.page(previous.after, HistoryDirection.APPEND, 30).entries)
    }

    @Test fun insertionsAndDeletedAnchorsDoNotShiftAppendBoundaries() {
        val index = populated(90)
        val first = index.page(limit = 30)
        index.put(HistoryCursor(1001, "new"), -1)
        index.remove(checkNotNull(first.after))
        assertEquals((30..59).toList(), index.page(first.after, HistoryDirection.APPEND, 30).entries)
    }

    @Test fun refreshKeepsAnInteriorAnchorAndFillsNearBothEnds() {
        val index = populated(100)
        assertEquals((35..64).toList(), index.page(key(50), limit = 30).entries)
        index.remove(key(50))
        assertEquals((35..49).toList() + (51..65).toList(), index.page(key(50), limit = 30).entries)
        assertEquals((0..29).toList(), index.page(key(0), limit = 30).entries)
        assertEquals((70..99).toList(), index.page(key(99), limit = 30).entries)
    }

    @Test fun emptyAndExactSizePagesReportTheirEndsWithoutAnExtraLoad() {
        val empty = HistoryIndex<Int>().page()
        assertTrue(empty.entries.isEmpty()); assertNull(empty.before); assertNull(empty.after)
        val page = populated(30).page(limit = 30)
        assertEquals(30, page.entries.size)
        assertNull(page.before); assertNull(page.after)
        val single = populated(1).page(key(0), limit = 1)
        assertEquals(listOf(0), single.entries)
        assertNull(single.before); assertNull(single.after)
    }

    @Test fun replacingAnEntryPreservesPositionAndPreviouslyReturnedPages() {
        val index = populated(40)
        val before = index.page()
        index.put(key(10), 999)
        val after = index.page()
        assertEquals(10, before.entries[10])
        assertEquals(999, after.entries[10])
        assertEquals(before.before, after.before); assertEquals(before.after, after.after)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroPageSizeIsRejected() { populated(1).page(limit = 0) }

    private fun key(index: Int) = HistoryCursor(1000L - index, index.toString())
    private fun populated(count: Int) = HistoryIndex<Int>().apply {
        repeat(count) { put(key(it), it) }
    }
}
