package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.history.HistoryMetadata
import java.io.File
import java.util.Properties

/** Private provenance for working text, including history-disabled/recovery paths.
 * It contains identifiers only; exports and clipboard text never include it. */
data class TranscriptSource(val audioId: String? = null, val transcriptId: String? = null,
    val modelName: String = "", val modelId: String? = null) {
    fun write(file: File) {
        val properties = Properties().apply {
            audioId?.let { setProperty("audioId", it) }
            transcriptId?.let { setProperty("transcriptId", it) }
            setProperty("modelName", modelName)
            modelId?.let { setProperty("modelId", it) }
        }
        HistoryMetadata.write(metadata(file), properties)
    }

    companion object {
        fun metadata(file: File) = File(file.parentFile, file.name + ".source")
        fun read(file: File): TranscriptSource {
            val metadata = metadata(file)
            if (!metadata.isFile) return TranscriptSource()
            val p = HistoryMetadata.read(metadata)
            return TranscriptSource(p.getProperty("audioId"), p.getProperty("transcriptId"),
                p.getProperty("modelName", ""), p.getProperty("modelId"))
        }
        fun retentionReference(file: File): Long {
            if (!file.name.endsWith(".source")) return file.lastModified()
            val transcript = File(file.parentFile, file.name.removeSuffix(".source"))
            return maxOf(file.lastModified(), transcript.lastModified())
        }
    }
}
