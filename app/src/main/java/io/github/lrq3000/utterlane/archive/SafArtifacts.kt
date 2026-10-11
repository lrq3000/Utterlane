package io.github.lrq3000.utterlane.archive

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.lrq3000.utterlane.history.AudioAssetIO
import io.github.lrq3000.utterlane.history.AudioPart
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** Blocking provider operations. Archival calls run only in :archive, never under
 * parent history/journal locks. Opaque document IDs are used without path guessing. */
class SafArtifacts(private val context: Context) : ExternalArtifacts {
    var creationStarted = false
        private set
    private val resolver get() = context.contentResolver
    private fun tree(uri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))

    private fun requireFolder(uri: Uri, creating: Boolean = false) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Choose a document folder" }
        resolver.query(tree(uri), arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS), null, null, null)?.use {
            check(it.moveToFirst()) { "The selected folder is unavailable" }
            check(it.getString(0) == Document.MIME_TYPE_DIR) { "The destination is not a folder" }
            if (creating) check(it.getInt(1) and Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "The selected folder is read-only" }
        } ?: error("The selected folder is unavailable")
    }

    override fun create(folder: String, name: String, mime: String): String {
        creationStarted = false
        PendingArtifactName.requireValid(name)
        val selected = Uri.parse(folder)
        requireFolder(selected, creating = true)
        // Random job/generation names avoid routine enumeration and collisions.
        // Return immediately: the parent's durable receipt must precede more IO.
        creationStarted = true
        return checkNotNull(DocumentsContract.createDocument(resolver, tree(selected), mime.takeUnless { '*' in it } ?: "application/octet-stream", name)) {
            "Cannot create archival file; private input is preserved"
        }.toString()
    }

    override fun verifyCreated(uri: String, name: String) {
        resolver.query(Uri.parse(uri), arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_FLAGS), null, null, null)?.use {
            check(it.moveToFirst() && it.getString(0) == name) { "The provider changed the pending filename" }
            val flags = it.getInt(1)
            check(flags and Document.FLAG_SUPPORTS_DELETE != 0 && flags and Document.FLAG_SUPPORTS_WRITE != 0) {
                "The provider does not support writable, removable managed files"
            }
        } ?: error("Cannot inspect the created archival file")
    }

    override fun write(uri: String, body: (OutputStream) -> Unit) {
        val descriptor = checkNotNull(resolver.openFileDescriptor(Uri.parse(uri), "wt")) { "Cannot open archival output" }
        var written = 0L
        // AutoCloseOutputStream propagates provider/close errors. fsync is useful
        // for local document FDs; pipe-based providers acknowledge by closing.
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            body(object : OutputStream() {
                override fun write(value: Int) { output.write(value); written++ }
                override fun write(bytes: ByteArray, offset: Int, count: Int) { output.write(bytes, offset, count); written += count }
                override fun flush() = output.flush()
            })
            output.flush()
            try { android.system.Os.fsync(output.fd) }
            catch (e: android.system.ErrnoException) {
                if (e.errno != android.system.OsConstants.EINVAL && e.errno != android.system.OsConstants.ESPIPE) throw e
            }
        }
        // Provider close is the commit boundary. A disappearing/truncated target
        // must not cause the parent to relinquish its useful private input.
        resolver.query(Uri.parse(uri), arrayOf(Document.COLUMN_SIZE), null, null, null)?.use {
            check(it.moveToFirst()) { "The archival output disappeared before publication" }
            if (!it.isNull(0) && it.getLong(0) >= 0) check(it.getLong(0) == written) { "The provider has not committed the complete archival output" }
        } ?: error("Cannot verify the archival output")
    }

    override fun findExact(folder: String, name: String): String? {
        PendingArtifactName.requireValid(name)
        if (folder.startsWith("file:")) return privateFile(folder).resolve(name).takeIf { it.isFile }?.toURI()?.toString()
        val selected = Uri.parse(folder)
        requireFolder(selected)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(selected, DocumentsContract.getTreeDocumentId(selected))
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${Document.COLUMN_DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
        }
        val cursor = try { resolver.query(children, projection, args, null) }
            catch (_: IllegalArgumentException) { resolver.query(children, projection, Bundle.EMPTY, null) }
        return checkNotNull(cursor) { "Cannot reconcile the pending archival target" }.use { rows ->
            check(!rows.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) { "The provider is still loading; cleanup will retry" }
            check(rows.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty()) { "The provider could not list pending targets" }
            // Providers may ignore the filter. Walk the cursor, not a listFiles()
            // array. Only exceptional lost-receipt recovery reaches this code.
            val match = PendingArtifactName.resolve(name, sequence {
                while (rows.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    yield(rows.getString(1) to DocumentsContract.buildDocumentUriUsingTree(selected, rows.getString(0)).toString())
                }
            })
            check(!rows.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) { "Incomplete provider listing" }
            check(rows.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty()) { "Incomplete provider listing" }
            match
        }
    }

    override fun delete(uri: String): Boolean {
        if (uri.startsWith("file:")) return privateFile(uri).let { !it.exists() || it.delete() }
        val target = Uri.parse(uri)
        // An unmounted/revoked tree is not evidence that its child was deleted.
        requireFolder(target)
        try {
            if (DocumentsContract.deleteDocument(resolver, target)) return true
            // A null/error cursor is not proof of absence. A definitive empty
            // result, or FileNotFound with a still-accessible tree, is idempotent.
            return resolver.query(target, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { !it.moveToFirst() } ?: false
        } catch (_: FileNotFoundException) {
            requireFolder(target)
            return true
        }
    }
    private fun privateFile(uri: String): java.io.File = java.io.File(java.net.URI(uri)).also {
        require(it.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath())) { "Private archival target escaped app storage" }
    }
}

class AndroidAudioAssetIO(private val context: Context) : AudioAssetIO {
    // Availability here is the metadata-level publication check used by deletion
    // rechecks too. Provider access is attempted only by consumers outside locks.
    override fun available(part: AudioPart) = part.uri != null || part.local.isFile
    override fun open(part: AudioPart): InputStream {
        val uri = part.readUri
        return if (uri == null) part.local.inputStream() else
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(uri))) { "Saved audio is unavailable; check folder access" }
    }
}
