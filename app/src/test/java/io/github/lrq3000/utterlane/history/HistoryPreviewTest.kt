package io.github.lrq3000.utterlane.history

import java.io.IOException
import java.io.Reader
import org.junit.Assert.*
import org.junit.Test

class HistoryPreviewTest {
    @Test fun skipsBlankLinesButNeverCombinesSeparateUtterances() {
        assertEquals("Speaker 1: Bonjour !", HistoryPreview.read("\r\n \t\r\n\u2003\n  Speaker 1: Bonjour !  \r\nSpeaker 2: Salut".reader()))
    }

    @Test fun emptyAndWhitespaceOnlyFilesHaveNoInventedTitle() {
        for (text in listOf("", " \t\r\n\u2003")) assertEquals("", HistoryPreview.read(text.reader()))
    }

    @Test fun handlesShortReadsWithoutMistakingThemForEndOfFile() {
        val input = CountingReader("\n\n  A complete first line\nAnother line", chunk = 1)
        assertEquals("A complete first line", HistoryPreview.read(input))
    }

    @Test fun arbitrarilyLongWhitespaceCannotCauseAnUnboundedScan() {
        val input = CountingReader(" ".repeat(100_000) + "Hidden past the budget")
        assertEquals("", HistoryPreview.read(input))
        assertTrue("Do not read past the fixed 4096-character budget", input.consumed <= 4096)
    }

    @Test fun longSingleLineIsBoundedAndMarkedAsTruncated() {
        val input = CountingReader("x".repeat(100_000))
        assertEquals("x".repeat(160) + "…", HistoryPreview.read(input))
        assertTrue(input.consumed <= 4096)
    }

    @Test fun truncationDoesNotSplitASupplementaryUnicodeCharacter() {
        assertEquals("a".repeat(159) + "…", HistoryPreview.read(("a".repeat(159) + "😀 more").reader()))
    }

    @Test fun shortLineCutByTheReadBudgetIsStillMarkedIncomplete() {
        val input = CountingReader("\n".repeat(4090) + "sentence continues")
        assertEquals("senten…", HistoryPreview.read(input))
        assertTrue(input.consumed <= 4096)
    }

    @Test fun payloadFailuresPropagateToThePagingRetryState() {
        val broken = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int = throw IOException("unavailable")
            override fun close() = Unit
        }
        assertThrows(IOException::class.java) { HistoryPreview.read(broken) }
    }

    /** A real sequential reader with short-read control and observable work, not
     * a pre-truncated fixture that would hide an accidentally unbounded scan. */
    private class CountingReader(private val text: String, private val chunk: Int = Int.MAX_VALUE) : Reader() {
        var consumed = 0
            private set
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (consumed == text.length) return -1
            val count = minOf(length, chunk, text.length - consumed)
            text.toCharArray(buffer, offset, consumed, consumed + count)
            consumed += count
            return count
        }
        override fun close() = Unit
    }
}
