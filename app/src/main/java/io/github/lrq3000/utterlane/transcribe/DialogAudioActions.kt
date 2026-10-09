package io.github.lrq3000.utterlane.transcribe

import android.content.ClipData
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.HistoryEntry
import io.github.lrq3000.utterlane.history.HistoryExports
import java.io.File
import java.io.FilterInputStream
import java.io.InterruptedIOException

/** Audio actions own bounded IO and never delete the source supplied by another app. */
class DialogAudioActions(private val app: UtterlaneApp) {
    fun import(uri: Uri?, path: String?): HistoryEntry {
        val source = uri ?: Uri.fromFile(File(requireNotNull(path)))
        val name = if (source.scheme == "file") source.lastPathSegment.orEmpty() else
            app.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else "audio"
            } ?: "audio"
        val extension = name.substringAfterLast('.', "audio")
        val mime = app.contentResolver.getType(source) ?: when (extension.lowercase()) {
            "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "m4a", "mp4" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"; else -> "audio/*"
        }
        val duration = runCatching {
            MediaMetadataRetriever().let { retriever ->
                try { retriever.setDataSource(app, source); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 }
                finally { retriever.release() }
            }
        }.getOrDefault(0)
        val raw = checkNotNull(app.contentResolver.openInputStream(source)) { "Cannot open audio" }
        // runInterruptible can stop large local copies between read operations.
        val input = object : FilterInputStream(raw) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio import cancelled")
                return super.read(buffer, offset, length)
            }
        }
        return input.use { app.recordingHistory.importAudio(it, extension, mime, duration) }
    }

    fun share(id: String): Intent {
        val entry = app.recordingHistory.get(id)
        val files = HistoryExports.create(app.recordingHistory, id, File(app.cacheDir, "history-exports"))
        val uris = ArrayList(files.map { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it) })
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = entry.mimeType
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("Audio recording", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun export(id: String, destination: Uri, directory: Boolean) {
        app.recordingHistory.acquire(id).use {
            val entry = app.recordingHistory.get(id)
            val parent = if (directory) checkNotNull(DocumentFile.fromTreeUri(app, destination)) else null
            for (part in 0 until entry.parts) {
                val source = entry.part(part)
                val target = if (parent != null) checkNotNull(parent.createFile(entry.mimeType, source.name)) { "Cannot create output file" }.uri else destination
                check(directory || entry.parts == 1) { "Choose a folder for multipart recordings" }
                try {
                    source.inputStream().use { input ->
                        checkNotNull(app.contentResolver.openOutputStream(target, "wt")) { "Cannot write destination" }.use { output -> input.copyTo(output, 64 * 1024) }
                    }
                } catch (e: Exception) {
                    // Only remove a new file we created in the chosen directory.
                    // A provider can fail again during cleanup (for example after
                    // revoking access). Keep the write failure and its errno intact.
                    if (directory) try { DocumentFile.fromSingleUri(app, target)?.delete() }
                    catch (cleanup: Exception) { if (cleanup !== e) e.addSuppressed(cleanup) }
                    throw e
                }
            }
        }
    }
}
