package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.history.HistoryMetadata
import io.github.lrq3000.utterlane.history.TranscriptHistory
import java.io.File
import java.util.Properties

data class WorkingTranscriptCopy(val file: File, val source: TranscriptSource) {
    val id: String get() = source.transcriptId ?: TranscriptHistory.idForAttempt(file.name)
}

/** Private provenance for working text, including history-disabled/recovery paths.
 * It contains identifiers only; exports and clipboard text never include it. */
data class TranscriptSource(val audioId: String? = null, val transcriptId: String? = null,
    val modelName: String = "", val modelId: String? = null, val discarded: Boolean = false) {
    fun write(file: File) {
        synchronized(writeLock) {
            // Late producer metadata must not clear a durable deletion marker.
            // Serialize the check/write and the shared atomic temporary filename.
            check(discarded || !read(file).discarded) { "Transcript was deleted" }
            val properties = Properties().apply {
                audioId?.let { setProperty("audioId", it) }
                transcriptId?.let { setProperty("transcriptId", it) }
                setProperty("modelName", modelName)
                modelId?.let { setProperty("modelId", it) }
                setProperty("discarded", discarded.toString())
            }
            HistoryMetadata.write(metadata(file), properties)
        }
    }

    companion object {
        private val writeLock = Any()
        fun metadata(file: File) = File(file.parentFile, file.name + ".source")
        /** Read provenance, never transcript bodies. This cold-path scan includes
         * recovery copies from previous owners/processes, not just the open dialog. */
        fun copies(directory: File): List<WorkingTranscriptCopy> = directory.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile || (file.extension != "txt" && !metadata(file).isFile) || file.length() == 0L) return@mapNotNull null
            val source = try { read(file) } catch (error: java.io.FileNotFoundException) {
                if (!file.isFile) return@mapNotNull null else throw error
            }
            if (source.discarded) null else WorkingTranscriptCopy(file, source)
        }
        fun read(file: File): TranscriptSource {
            val metadata = metadata(file)
            if (!metadata.isFile) return TranscriptSource()
            val p = HistoryMetadata.read(metadata)
            return TranscriptSource(p.getProperty("audioId"), p.getProperty("transcriptId"),
                p.getProperty("modelName", ""), p.getProperty("modelId"), p.getProperty("discarded", "false").toBoolean())
        }
        fun retentionReference(file: File): Long {
            if (!file.name.endsWith(".source")) return file.lastModified()
            val transcript = File(file.parentFile, file.name.removeSuffix(".source"))
            return maxOf(file.lastModified(), transcript.lastModified())
        }
    }
}
