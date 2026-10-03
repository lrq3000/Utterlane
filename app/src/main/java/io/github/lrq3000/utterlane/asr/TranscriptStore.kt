package io.github.lrq3000.utterlane.asr

import java.io.File
import java.io.RandomAccessFile
import java.io.Closeable

/** Text grows on disk, not in Compose state. Full exports use the file directly. */
class TranscriptStore(val file: File) {
    companion object {
        const val PREVIEW_LIMIT = 8000
        const val TRANSFER_LIMIT = 64000
    }
    private var tail = ""
    var segments = 0
        private set
    private val owner: Closeable

    init {
        if (!file.exists()) check(file.createNewFile()) { "Cannot create transcript" }
        // Recovery reconstructs only the bounded final page, not the full text.
        tail = page((file.length() - PREVIEW_LIMIT).coerceAtLeast(0))
        owner = CacheArtifacts.acquire(file)
    }

    fun acquire(): Closeable = CacheArtifacts.acquire(file)
    fun dispose() { CacheArtifacts.deleteWhenReleased(file); owner.close() }
    fun keepForRecovery() = owner.close()

    @Synchronized fun append(text: String) {
        if (text.isBlank()) return
        val delta = (if (file.length() > 0) " " else "") + text.trim()
        file.appendText(delta, Charsets.UTF_8)
        tail = (tail + delta).takeLast(PREVIEW_LIMIT).let { if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it }
        segments++
    }

    @Synchronized fun preview(): String = tail

    @Synchronized fun readForTransfer(limit: Int = TRANSFER_LIMIT): String? =
        if (file.length() <= limit) file.readText(Charsets.UTF_8) else null

    fun snapshot(): File {
        acquire().use { return snapshotWithLease() }
    }

    private fun snapshotWithLease(): File {
        val length = synchronized(this) { file.length() }
        val directory = File(file.parentFile, "exports")
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create text export" }
        val snapshot = File.createTempFile("export-", ".txt", directory)
        try {
            CacheArtifacts.acquire(snapshot).use {
                file.inputStream().use { input ->
                    snapshot.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var remaining = length
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            check(count > 0) { "Transcript became unavailable" }
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
            return snapshot
        } catch (e: Exception) { snapshot.delete(); throw e }
    }

    // UTF-8 may use four bytes per character. Pages are byte-bounded and exclude
    // leading continuation bytes so multibyte text is not corrupted at page starts.
    @Synchronized fun page(offset: Long, length: Int = PREVIEW_LIMIT): String {
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset.coerceIn(0, input.length()))
            val bytes = ByteArray(minOf(length.toLong(), input.length() - input.filePointer).toInt())
            input.readFully(bytes)
            var start = 0
            while (start < bytes.size && bytes[start].toInt() and 0xc0 == 0x80) start++
            var end = bytes.size
            // Include only complete UTF-8 sequences; a page's overlap can recover a
            // word at its edge without retaining an unbounded index in memory.
            if (end > start) {
                var lead = end - 1
                while (lead > start && bytes[lead].toInt() and 0xc0 == 0x80) lead--
                val value = bytes[lead].toInt() and 0xff
                val expected = when { value < 0x80 -> 1; value < 0xe0 -> 2; value < 0xf0 -> 3; else -> 4 }
                if (end - lead < expected) end = lead
            }
            return String(bytes, start, end - start, Charsets.UTF_8)
        }
    }
}
