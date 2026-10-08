package io.github.lrq3000.utterlane.asr

import java.io.File
import java.io.RandomAccessFile

/** Random-access, bounded text chunks for a continuous reader. Nominal byte
 * boundaries are adjusted identically on both sides, so concatenating chunks
 * reconstructs the source exactly, including four-byte UTF-8 and whitespace. */
class TranscriptDocument(private val file: File, val length: Long = file.length(),
    private val chunkBytes: Int = 2048) {
    val chunkCount: Int
    init {
        require(chunkBytes >= 4 && length >= 0)
        val count = length / chunkBytes + if (length % chunkBytes == 0L) 0 else 1
        require(count <= Int.MAX_VALUE) { "Transcript is too large to display; use sharing" }
        chunkCount = count.toInt()
    }

    fun read(index: Int): String {
        require(index in 0 until chunkCount)
        return RandomAccessFile(file, "r").use { input ->
            check(input.length() >= length) { "Transcript became unavailable" }
            val start = boundary(input, index.toLong() * chunkBytes)
            val end = boundary(input, minOf(length, (index.toLong() + 1) * chunkBytes))
            val bytes = ByteArray((end - start).toInt())
            input.seek(start); input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun boundary(input: RandomAccessFile, nominal: Long): Long {
        if (nominal == 0L || nominal >= length) return nominal.coerceAtMost(length)
        input.seek(nominal)
        var start = nominal
        while (start < length && input.read().and(0xc0) == 0x80) start++
        // Prefer a nearby word boundary to avoid visually splitting a word across
        // Text items. Long unbroken strings still use a bounded code-point boundary.
        input.seek(start)
        val limit = minOf(length, start + 64)
        while (input.filePointer < limit) {
            val value = input.read()
            if (value == 32 || value == 10 || value == 9 || value == 13) return input.filePointer
        }
        return if (limit == length) length else start
    }
}
