package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.CacheArtifacts
import java.io.File
import java.util.UUID

/** A recipient's URI points to an immutable export, independent of history pruning. */
object HistoryExports {
    data class Snapshot(val files: List<File>, val mimeType: String, val representation: String)
    fun create(history: RecordingHistory, id: String, root: File): List<File> = createSnapshot(history, id, root).files
    fun createSnapshot(history: RecordingHistory, id: String, root: File): Snapshot =
        history.selectForExport(id).use { createSnapshot(it, root) }
    fun createSnapshot(selection: AudioExportSelection, root: File): Snapshot {
            check(root.mkdirs() || root.isDirectory) { "Cannot create audio export" }
            val directory = File(root, UUID.randomUUID().toString())
            check(directory.mkdir()) { "Cannot create audio export" }
            CacheArtifacts.acquire(directory).use {
                try {
                    val files = (0 until selection.parts).map { part ->
                        File(directory, selection.part(part).name).also { output ->
                            output.outputStream().use { selection.copyPartTo(part, it) }
                        }
                    }
                    return Snapshot(files, selection.mimeType, selection.representation)
                } catch (e: Exception) { directory.deleteRecursively(); throw e }
            }
    }
}
