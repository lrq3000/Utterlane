package io.github.lrq3000.utterlane.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal interface DiagnosticStorage {
    fun append(bytes: ByteArray): Boolean
    fun snapshot(): File
    fun clear()
    fun prune()
}

/** Owned exclusively by the log's I/O coroutine. No filesystem call holds a producer lock. */
internal class DiagnosticRingFiles(
    directory: () -> File,
    private val maxFileBytes: Int = 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis
) : DiagnosticStorage {
    constructor(directory: File, maxFileBytes: Int = 1024 * 1024, clock: () -> Long = System::currentTimeMillis) :
        this({ directory }, maxFileBytes, clock)
    // Context.cacheDir may create its parent. Resolve even that path on the I/O owner.
    private val directory by lazy(directory)
    private val current by lazy { File(this.directory, "current.jsonl") }
    private val previous by lazy { File(this.directory, "previous.jsonl") }
    private val exports by lazy { File(this.directory, "exports") }
    private var prepared = false
    init { require(maxFileBytes > 0) }

    override fun append(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > maxFileBytes || bytes.last() != '\n'.code.toByte()) return false
        prune()
        check(directory.isDirectory || directory.mkdirs())
        if (current.length() + bytes.size > maxFileBytes) {
            delete(previous)
            check(current.renameTo(previous)) { "Diagnostic rotation failed" }
        }
        val before = current.length()
        try { FileOutputStream(current, true).use { it.write(bytes) } }
        catch (failure: Exception) {
            // A disk-full/short write must not poison every later exported JSONL record.
            RandomAccessFile(current, "rw").use { it.setLength(before) }
            throw failure
        }
        return true
    }

    override fun snapshot(): File {
        prune()
        check(exports.isDirectory || exports.mkdirs())
        // Each export has a new immutable URI. Bound retained exports before creating
        // another; the third export expires the oldest grant's backing file.
        val snapshots = exportFiles().sortedBy { it.lastModified() }
        snapshots.take((snapshots.size - 1).coerceAtLeast(0)).forEach(::delete)
        val target = File(exports, "diagnostics-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                listOf(previous, current).filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return target
        } catch (failure: Exception) { delete(target); throw failure }
    }

    override fun clear() {
        listOf(current, previous).forEach(::delete)
        exportFiles().forEach(::delete)
    }

    override fun prune() {
        val now = clock()
        listOf(current, previous).forEach { file ->
            if (file.exists() && (file.length() > maxFileBytes || now - file.lastModified() >= 7 * 86400000L)) delete(file)
            if (!prepared && file.isFile) repairTail(file)
        }
        prepared = true
        exportFiles().filter { now - it.lastModified() >= 86400000L }.forEach(::delete)
    }

    private fun exportFiles(): List<File> = exports.listFiles { file ->
        file.name.matches(EXPORT_NAME) && file.isFile
    }?.toList().orEmpty()
    private fun delete(file: File) { check(!file.exists() || file.delete()) { "Diagnostic cleanup failed" } }
    private fun repairTail(file: File) {
        // At most 1 MiB per file, once after process start. A process kill can leave
        // an incomplete final write; preserve only newline-terminated whole records.
        val bytes = file.readBytes()
        if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) {
            val end = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
            RandomAccessFile(file, "rw").use { it.setLength(end.toLong()) }
        }
    }
    companion object { private val EXPORT_NAME = Regex("diagnostics-[a-f0-9-]+\\.zip") }
}
