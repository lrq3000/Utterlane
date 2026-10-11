package io.github.lrq3000.utterlane.transcribe

import android.content.ClipData
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.AudioImportMetadata
import io.github.lrq3000.utterlane.history.ImportRetentionChoice
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.history.HistoryExports
import io.github.lrq3000.utterlane.history.AudioExportSelection
import java.io.File

/** Audio actions own bounded IO and never delete the source supplied by another app. */
class DialogAudioActions(private val app: UtterlaneApp) {
    data class InspectedSource(val uri: Uri, val metadata: AudioImportMetadata)

    /** Blocking metadata work only; call off Main before asking permission to
     * retain a large source. Missing provider/track facts stay null, never zero. */
    fun inspect(uri: Uri?, path: String?): InspectedSource {
        val source = uri ?: Uri.fromFile(File(requireNotNull(path)))
        var name: String? = null
        var size: Long? = null
        if (source.scheme == "file") {
            source.path?.let(::File)?.let { file ->
                name = file.name.takeIf { it.isNotBlank() }
                if (file.isFile) size = file.length()
            }
        } else runCatching {
            app.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !it.isNull(nameColumn)) name = it.getString(nameColumn)?.takeIf { it.isNotBlank() }
                    if (sizeColumn >= 0 && !it.isNull(sizeColumn)) size = it.getLong(sizeColumn).takeIf { bytes -> bytes >= 0 }
                }
            }
        }
        var mime = runCatching { app.contentResolver.getType(source)?.takeIf { it.isNotBlank() } }.getOrNull()
        var duration: Long? = null
        var trackMime: String? = null
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(app, source, null)
                // Match the decoder's selected audio track. Compression describes
                // that actual track, independently of the container or extension.
                val track = AudioDecodeMetadata.audioTrack(extractor)
                trackMime = track.mime
                duration = AudioDecodeMetadata.durationUs(track.format).takeIf { it > 0 }?.div(1000)
            } finally { extractor.release() }
        }
        if (duration == null || mime == null) runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, source)
                if (duration == null) duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it >= 0 }
                if (mime == null) mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.isNotBlank() }
            } finally { retriever.release() }
        }
        val compressed = trackMime?.takeIf { it.startsWith("audio/") && it != "audio/*" }?.let { it != "audio/raw" }
        return InspectedSource(source, AudioImportMetadata(name, mime, duration, size, trackMime, compressed))
    }

    /** Byte-for-byte, bounded copy. The returned owner protects ordinary retained
     * history from pruning through model preparation and transcription. */
    fun import(source: InspectedSource, retention: ImportRetentionChoice): RecordingHistory.OwnedImport {
        val raw = checkNotNull(app.contentResolver.openInputStream(source.uri)) { "Cannot open audio" }
        val extension = source.metadata.displayName?.substringAfterLast('.', "audio") ?: "audio"
        return copyImportedAudio(app.recordingHistory, raw, extension, source.metadata, retention)
    }

    fun share(id: String): Intent {
        val snapshot = HistoryExports.createSnapshot(app.recordingHistory, id, File(app.cacheDir, "history-exports"))
        val uris = ArrayList(snapshot.files.map { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it) })
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = snapshot.mimeType
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("Audio recording", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun export(selection: AudioExportSelection, destination: Uri, directory: Boolean) {
            val parent = if (directory) checkNotNull(DocumentFile.fromTreeUri(app, destination)) else null
            check(directory || selection.parts == 1) { "Choose a folder for multipart recordings" }
            for (part in 0 until selection.parts) {
                val source = selection.part(part)
                val target = if (parent != null) checkNotNull(parent.createFile(source.mimeType, source.name)) { "Cannot create output file" }.uri else destination
                try {
                    checkNotNull(app.contentResolver.openOutputStream(target, "wt")) { "Cannot write destination" }
                        .use { output -> selection.copyPartTo(part, output) }
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
