package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.CacheArtifacts
import java.io.File
import java.util.UUID

/** A recipient's URI points to an immutable export, independent of history pruning. */
object HistoryExports {
    fun create(history: RecordingHistory, id: String, root: File): List<File> {
        history.acquire(id).use {
            val entry = history.get(id)
            check(entry.parts > 0) { "Recording contains no saved audio" }
            check(root.mkdirs() || root.isDirectory) { "Cannot create audio export" }
            val directory = File(root, UUID.randomUUID().toString())
            check(directory.mkdir()) { "Cannot create audio export" }
            CacheArtifacts.acquire(directory).use {
                try {
                    return (0 until entry.parts).map { part ->
                        File(directory, "audio-$part.wav").also { entry.part(part).copyTo(it) }
                    }
                } catch (e: Exception) { directory.deleteRecursively(); throw e }
            }
        }
    }
}
