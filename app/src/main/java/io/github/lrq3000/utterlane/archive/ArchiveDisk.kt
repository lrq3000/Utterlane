package io.github.lrq3000.utterlane.archive

import java.io.Closeable
import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID

/** Private metadata only. Directory traversal owns and closes its cursor even
 * when a visitor stops early; no listFiles()/sorted whole-directory snapshots. */
internal object ArchiveDisk {
    fun key(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    fun owner(owner: ArtifactOwner) = key("${owner.kind}:${owner.id}")
    fun slot(request: ArchivalRequest) = key("${request.operation}\u0000${request.part}\u0000${request.destination}" +
        if (request.representation == AudioRepresentation.ORIGINAL) "" else "\u0000${request.representation}")
    fun read(file: File) = Properties().apply { file.inputStream().use(::load) }
    /** Our legacy Properties.store files have one escaped property per physical
     * line. Stream large old cleanup sets instead of materializing that record.
     * Refuse foreign continuation syntax rather than silently dropping intent. */
    fun visitLegacyProperties(file: File, action: (String, String) -> Unit) {
        file.bufferedReader(Charsets.ISO_8859_1).useLines { lines -> lines.forEach { line ->
            val start = line.trimStart()
            if (start.isEmpty() || start.startsWith('#') || start.startsWith('!')) return@forEach
            require(line.takeLastWhile { it == '\\' }.length % 2 == 0) { "Unsupported continuation in archival metadata" }
            val property = Properties().apply { load(java.io.StringReader(line)) }
            property.forEach { key, value -> action(key as String, value as String) }
        } }
    }
    fun write(file: File, properties: Properties) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val pending = File(file.parentFile, file.name + ".tmp")
        pending.outputStream().use { properties.store(it, "Utterlane managed archival"); it.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun remove(file: File) { check(!file.exists() || file.delete()) { "Cannot remove archival metadata: $file" } }
    fun marker(file: File) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        check(file.isFile || file.createNewFile())
    }
    fun move(from: File, to: File) {
        check(to.parentFile!!.mkdirs() || to.parentFile!!.isDirectory)
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun clearDerived(directory: File) {
        if (!directory.exists()) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
    fun visit(directory: File, suffix: String, visitor: (File) -> Boolean) {
        Cursor(directory, suffix).use { cursor -> while (true) { val file = cursor.next() ?: break; if (!visitor(file)) break } }
    }
    fun first(directory: File, suffix: String): File? = Cursor(directory, suffix).use { it.next() }
    class Cursor(directory: File, private val suffix: String) : Closeable {
        private val stream: DirectoryStream<Path>? = if (directory.isDirectory) Files.newDirectoryStream(directory.toPath()) else null
        private val iterator = stream?.iterator()
        fun next(): File? {
            val entries = iterator ?: return null
            while (entries.hasNext()) {
                val file = entries.next().toFile()
                if (file.name.endsWith(suffix) && file.exists()) return file
            }
            return null
        }
        override fun close() { stream?.close() }
    }
}

internal fun Properties.owner() = ArtifactOwner(ArtifactKind.valueOf(getProperty("kind")), getProperty("owner"))
internal fun Properties.putOwner(owner: ArtifactOwner) { setProperty("kind", owner.kind.name); setProperty("owner", owner.id) }
