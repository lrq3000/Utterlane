package io.github.lrq3000.utterlane.archive

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only fault fixture. IDs are opaque UUIDs, not file paths. A blocked
 * provider Binder call proves that killing :archive does not need its cooperation. */
class ArchiveTestDocumentsProvider : DocumentsProvider() {
    private val root get() = File(requireNotNull(context).cacheDir, "archive-provider-fixture").apply { mkdirs() }
    @Volatile private var gate = CountDownLatch(1)
    private val blocked = AtomicInteger()
    @Volatile private var deny = false
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())
    private fun checkAccess() { if (deny) throw SecurityException("Fixture folder access revoked") }
    private fun metadata(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(root, "$id.properties")
    }
    private fun read(id: String) = Properties().apply { metadata(id).inputStream().use(::load) }
    private fun row(cursor: MatrixCursor, id: String) {
        val directory = id == "root" || id == "blocked"
        val p = if (directory) Properties() else read(id)
        val values: Map<String, Any> = mapOf(Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to if (directory) id else p.getProperty("name"),
            Document.COLUMN_MIME_TYPE to if (directory) Document.MIME_TYPE_DIR else p.getProperty("mime"),
            Document.COLUMN_FLAGS to if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE),
            Document.COLUMN_SIZE to if (directory) 0L else File(root, "$id.data").length())
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        checkAccess()
        return MatrixCursor(projection ?: columns).also { row(it, documentId) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        checkAccess()
        return MatrixCursor(projection ?: columns).also { cursor ->
            root.listFiles().orEmpty().filter { it.extension == "properties" }.forEach { file ->
                if (read(file.nameWithoutExtension).getProperty("parent") == parentDocumentId) row(cursor, file.nameWithoutExtension)
            }
        }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == documentId || runCatching { read(documentId).getProperty("parent") == parentDocumentId }.getOrDefault(false)
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        checkAccess()
        val id = UUID.randomUUID().toString()
        metadata(id).outputStream().use { out -> Properties().apply {
            setProperty("name", displayName); setProperty("mime", mimeType); setProperty("parent", parentDocumentId)
        }.store(out, "fixture") }
        File(root, "$id.data").createNewFile()
        return id
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        checkAccess()
        val p = read(documentId)
        if (p.getProperty("parent") == "blocked" && mode.contains('w')) {
            blocked.incrementAndGet()
            try { gate.await() } finally { blocked.decrementAndGet() }
        }
        return ParcelFileDescriptor.open(File(root, "$documentId.data"), ParcelFileDescriptor.parseMode(mode))
    }
    override fun deleteDocument(documentId: String) {
        checkAccess()
        if (!metadata(documentId).exists()) throw FileNotFoundException(documentId)
        check(File(root, "$documentId.data").delete())
        check(metadata(documentId).delete())
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "fixture.status" -> Bundle().apply { putInt("blocked", blocked.get()) }
        "fixture.block" -> { gate = CountDownLatch(1); Bundle.EMPTY }
        "fixture.unblock" -> { gate.countDown(); Bundle.EMPTY }
        "fixture.deny" -> { deny = arg == "true"; Bundle.EMPTY }
        else -> super.call(method, arg, extras)
    }
}
