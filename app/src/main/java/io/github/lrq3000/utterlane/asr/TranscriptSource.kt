package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.history.HistoryMetadata
import io.github.lrq3000.utterlane.history.TranscriptHistory
import java.io.File
import java.util.Properties

data class WorkingTranscriptCopy(val file: File, val source: TranscriptSource) {
    val id: String get() = source.transcriptId ?: TranscriptHistory.idForAttempt(file.name)
}

class TranscriptDiscardedException : IllegalStateException("Transcript was deleted")

/** Private provenance for working text, including history-disabled/recovery paths.
 * It contains identifiers only; exports and clipboard text never include it. */
data class TranscriptSource(val audioId: String? = null, val transcriptId: String? = null,
    val modelName: String = "", val modelId: String? = null, val discarded: Boolean = false,
    val recovered: Boolean = false) {
    fun write(file: File) {
        synchronized(lockFor(file)) {
            // Late producer metadata must not clear a durable deletion marker.
            // Serialize the check/write and the shared atomic temporary filename.
            val previous = read(file)
            if (!discarded && previous.discarded) throw TranscriptDiscardedException()
            val properties = Properties().apply {
                audioId?.let { setProperty("audioId", it) }
                transcriptId?.let { setProperty("transcriptId", it) }
                setProperty("modelName", modelName)
                modelId?.let { setProperty("modelId", it) }
                setProperty("discarded", discarded.toString())
                // Origin is monotonic: an older producer snapshot may add an ID,
                // but cannot erase a recovery acknowledgement from another owner.
                setProperty("recovered", (recovered || previous.recovered).toString())
            }
            HistoryMetadata.write(metadata(file), properties)
        }
    }

    companion object {
        // Bounded striping avoids retaining a lock for every historical filename,
        // while unrelated metadata usually remains independent of a large save.
        private val sourceLocks = Array(64) { Any() }
        private fun lockFor(file: File) = sourceLocks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % sourceLocks.size]
        fun <T> withActiveSource(file: File, action: () -> T): T = synchronized(lockFor(file)) {
            if (read(file).discarded) throw TranscriptDiscardedException()
            action()
        }
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
                p.getProperty("modelName", ""), p.getProperty("modelId"), p.getProperty("discarded", "false").toBoolean(),
                p.getProperty("recovered", "false").toBoolean())
        }
        fun retentionReference(file: File): Long {
            if (!file.name.endsWith(".source")) return file.lastModified()
            val transcript = File(file.parentFile, file.name.removeSuffix(".source"))
            return maxOf(file.lastModified(), transcript.lastModified())
        }
    }
}
