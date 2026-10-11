package io.github.lrq3000.utterlane.archive

import java.io.File

/** Individually durable targets and creation intents. The ready/owner directories
 * are disposable indexes rebuilt from pending records, not published history. */
internal class ArchiveTargets(root: File, private val indexes: File) {
    private val root = File(root, "targets")
    private val ready get() = File(indexes, "cleanup")
    var count = 0
        private set
    var readyCount = 0
        private set
    var indexVisits = 0L
        private set
    private fun checked(value: String): String {
        require(value.isNotEmpty() && File(value).name == value && value != "." && value != ".." && '\\' !in value && '/' !in value)
        return value
    }
    private fun directory(jobId: String) = File(root, checked(jobId))
    private fun file(ref: ArchivalCleanup) = File(directory(ref.jobId), "${checked(ref.id)}.target")
    private fun ownerIndex(owner: ArtifactOwner) = File(indexes, "owner-targets/${ArchiveDisk.owner(owner)}")
    fun get(key: String): ArchivalCleanup? {
        val pieces = key.split('~')
        require(pieces.size == 2)
        val file = File(directory(pieces[0]), "${checked(pieces[1])}.target")
        return file.takeIf { it.isFile }?.let { ArchiveRecords.target(ArchiveDisk.read(it)) }
    }
    fun canonical(jobId: String, generation: Long) = "$jobId~${ArchiveDisk.key("$jobId:$generation") }"
    fun hasJob(jobId: String): Boolean = ArchiveDisk.first(directory(jobId), ".target") != null
    fun forJob(jobId: String, action: (ArchivalCleanup) -> Unit) = ArchiveDisk.visit(directory(jobId), ".target") {
        action(ArchiveRecords.target(ArchiveDisk.read(it))); true
    }
    fun forOwner(owner: ArtifactOwner, action: (ArchivalCleanup) -> Unit) = ArchiveDisk.visit(ownerIndex(owner), ".ref") {
        get(it.name.removeSuffix(".ref"))?.let(action); true
    }
    fun hasOwner(owner: ArtifactOwner) = ArchiveDisk.first(ownerIndex(owner), ".ref") != null
    fun ownerCursor(owner: ArtifactOwner) = OwnerCursor(owner)
    inner class OwnerCursor(owner: ArtifactOwner) : java.io.Closeable {
        private val files = ArchiveDisk.Cursor(ownerIndex(owner), ".ref")
        fun next(): ArchivalCleanup? {
            while (true) { val file = files.next() ?: return null; get(file.name.removeSuffix(".ref"))?.let { return it } }
        }
        override fun close() = files.close()
    }
    fun write(ref: ArchivalCleanup, eligible: Boolean) {
        val exists = file(ref).isFile
        ArchiveDisk.write(file(ref), ArchiveRecords.target(ref))
        if (!exists) count++
        ArchiveDisk.marker(File(ownerIndex(ref.owner), "${ref.key}.ref"))
        eligible(ref, eligible)
    }
    fun eligible(ref: ArchivalCleanup, value: Boolean) {
        val marker = File(ready, "${ref.key}.ref")
        if (value && !marker.exists()) { ArchiveDisk.marker(marker); readyCount++ }
        else if (!value && marker.exists()) { ArchiveDisk.remove(marker); readyCount-- }
    }
    fun remove(ref: ArchivalCleanup) {
        if (!file(ref).exists()) return
        ArchiveDisk.remove(file(ref)); count--
        eligible(ref, false)
        ArchiveDisk.remove(File(ownerIndex(ref.owner), "${ref.key}.ref"))
        ownerIndex(ref.owner).delete(); directory(ref.jobId).delete()
    }
    fun firstReady(): ArchivalCleanup? {
        while (readyCount > 0) {
            val marker = ArchiveDisk.first(ready, ".ref") ?: return null
            indexVisits++
            val ref = get(marker.name.removeSuffix(".ref"))
            if (ref != null) return ref
            ArchiveDisk.remove(marker); readyCount--
        }
        return null
    }
    fun rebuild(isEligible: (ArchivalCleanup) -> Boolean, redundant: (ArchivalCleanup) -> Boolean) {
        count = 0; readyCount = 0
        visit { ref ->
            if (redundant(ref)) ArchiveDisk.remove(file(ref))
            else {
                count++
                ArchiveDisk.marker(File(ownerIndex(ref.owner), "${ref.key}.ref"))
                eligible(ref, isEligible(ref))
            }
        }
    }
    fun cursor() = Cursor(root)
    fun visit(action: (ArchivalCleanup) -> Unit) = cursor().use { cursor ->
        while (true) { val ref = cursor.next() ?: break; action(ref) }
    }
    class Cursor(root: File) : java.io.Closeable {
        private val directories = ArchiveDisk.Cursor(root, "")
        private var directory: File? = null
        private var files: ArchiveDisk.Cursor? = null
        fun next(): ArchivalCleanup? {
            while (true) {
                files?.next()?.let { return ArchiveRecords.target(ArchiveDisk.read(it)) }
                files?.close(); files = null
                directory?.delete() // Only empty directories; no settled traversal debt.
                directory = directories.next() ?: return null
                if (directory!!.isDirectory) files = ArchiveDisk.Cursor(directory!!, ".target")
            }
        }
        override fun close() { files?.close(); directory?.delete(); directories.close() }
    }
    /** Bootstrap/migration writes happen before rebuilding derived counters. */
    fun writeUnindexed(ref: ArchivalCleanup) = ArchiveDisk.write(file(ref), ArchiveRecords.target(ref))
    fun removeUnindexed(ref: ArchivalCleanup) = ArchiveDisk.remove(file(ref))
}
