package io.github.lrq3000.utterlane.history

import java.io.IOException
import java.io.Reader

/** Bounded projection of transcript payloads for the history list. */
internal object HistoryPreview {
    private const val READ_BUDGET = 4096
    private const val PREVIEW_LENGTH = 160

    fun read(reader: Reader): String {
        // Never use readLine/lineSequence on the reader: a single whitespace line
        // can be arbitrarily large. Both IO and projection are O(READ_BUDGET),
        // regardless of payload size. The caller owns the reader and file lease.
        val buffer = CharArray(READ_BUDGET)
        var count = 0
        while (count < buffer.size) {
            val read = reader.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) throw IOException("Transcript preview reader made no progress")
            count += read
        }
        var start = 0
        while (start < count) {
            var end = start
            while (end < count && buffer[end] != '\n' && buffer[end] != '\r') end++
            val line = String(buffer, start, end - start).trim()
            if (line.isNotEmpty()) {
                val truncated = line.length > PREVIEW_LENGTH || (end == count && count == READ_BUDGET)
                var preview = line.take(PREVIEW_LENGTH)
                // A UTF-16 boundary must not turn an emoji into a replacement glyph.
                if (preview.last().isHighSurrogate()) preview = preview.dropLast(1)
                return preview + if (truncated) "…" else ""
            }
            start = end + 1
        }
        return ""
    }
}
