package io.github.lrq3000.utterlane.archive

import java.io.File
import java.util.Properties

/** Compact per-artifact URI authority and per-destination deduplication pointers.
 * Cancelled slots are minimal same-epoch producer fences, not execution records.
 * Deleted owners are direct disk lookups, never a lifetime resident set. */
internal class ArchiveCatalog(private val root: File) {
    val deleting = File(root, "deleting")
    private val deleted = File(root, "deleted")
    private val owners = File(root, "owners")
    fun slots(owner: ArtifactOwner) = File(owners, ArchiveDisk.owner(owner))
    fun slotFile(owner: ArtifactOwner, key: String) = File(slots(owner), "$key.slot")
    private fun publicationFile(owner: ArtifactOwner, id: String) = File(slots(owner), "$id.pub")
    private fun representationFile(owner: ArtifactOwner) = File(slots(owner), "active-representation.properties")
    fun activeRepresentation(owner: ArtifactOwner): String {
        val file = representationFile(owner)
        if (file.isFile) return ArchiveDisk.read(file).getProperty("representation")
        // Lazy compatibility for publications from the pre-representation schema.
        // New-schema interrupted commits are repaired from their execution record.
        var legacy: String? = null
        ArchiveDisk.visit(slots(owner), ".pub") { pub ->
            val p = ArchiveDisk.read(pub)
            if (p.getProperty("operation") == ArchivalOperation.ENCODE_AUDIO.name && p.getProperty("representation") == null) {
                val rep = "encoded-${p.getProperty("id")}" // stable across restarts
                check(legacy == null || legacy == rep) { "Ambiguous legacy encoded representations" }
                legacy = rep
            }
            true
        }
        val rep = legacy ?: AudioRepresentation.ORIGINAL
        ArchiveDisk.write(file, Properties().apply { setProperty("representation", rep) })
        return rep
    }
    private fun activate(job: ArchivalJob) {
        if (job.request.operation != ArchivalOperation.ENCODE_AUDIO) return
        val current = activeRepresentation(job.request.owner)
        // A stale older publication cannot replace a later representation.
        if (current == job.request.representation || current != job.request.sourceRepresentation) return
        ArchiveDisk.write(representationFile(job.request.owner), Properties().apply {
            setProperty("representation", job.request.representation)
        })
    }
    fun retired(p: Properties) = p.getProperty("retired", "false").toBoolean()
    fun replacementSettled(owner: ArtifactOwner, representation: String) = representationFile(owner).takeIf(File::isFile)
        ?.let { ArchiveDisk.read(it).getProperty("retiredThrough") == representation } == true
    fun replacementSettled(owner: ArtifactOwner, representation: String, settle: Boolean) {
        if (!settle || isDeleted(owner) || activeRepresentation(owner) != representation) return
        val file = representationFile(owner)
        ArchiveDisk.write(file, ArchiveDisk.read(file).apply { setProperty("retiredThrough", representation) })
    }
    fun retirePublication(file: File, p: Properties) {
        if (retired(p)) return
        p.setProperty("retired", "true"); ArchiveDisk.write(file, p)
    }
    fun publication(owner: ArtifactOwner, id: String): Properties? = publicationFile(owner, id).takeIf { it.isFile }?.let(ArchiveDisk::read)
    private fun readSlot(owner: ArtifactOwner, key: String): Properties? = slotFile(owner, key).takeIf { it.isFile }?.let(ArchiveDisk::read)
    fun read(owner: ArtifactOwner, key: String): Properties? {
        val p = readSlot(owner, key) ?: return null
        return if (p.getProperty("state") == ArchivalState.PUBLISHED.name) publication(owner, p.getProperty("id")) else p
    }
    fun read(request: ArchivalRequest) = read(request.owner, ArchiveDisk.slot(request))
    fun write(job: ArchivalJob, state: ArchivalState = job.state) {
        val pointer = Properties().apply {
            putOwner(job.request.owner); setProperty("id", job.id); setProperty("state", state.name); setProperty("epoch", job.epoch.toString())
        }
        if (state == ArchivalState.PUBLISHED) {
            // Authority is per artifact, not per deduplication slot. Migration
            // must preserve even older duplicate published representations.
            ArchiveDisk.write(publicationFile(job.request.owner, job.id), Properties().apply {
                putAll(pointer)
                setProperty("uri", checkNotNull(job.uri)); setProperty("generation", job.generation.toString())
                setProperty("destination", job.request.destination); setProperty("operation", job.request.operation.name)
                setProperty("part", job.request.part.toString())
                setProperty("representation", job.request.representation)
                setProperty("slot", ArchiveDisk.slot(job.request))
            })
            activate(job)
        }
        ArchiveDisk.write(slotFile(job.request.owner, ArchiveDisk.slot(job.request)), pointer)
    }
    fun repairPublicationIndex(job: ArchivalJob) {
        if (publication(job.request.owner, job.id)?.let(::retired) == true) return
        activate(job)
        if (read(job.request)?.getProperty("state") == ArchivalState.PUBLISHED.name) return
        ArchiveDisk.write(slotFile(job.request.owner, ArchiveDisk.slot(job.request)), Properties().apply {
            putOwner(job.request.owner); setProperty("id", job.id); setProperty("state", ArchivalState.PUBLISHED.name)
            setProperty("epoch", job.epoch.toString())
        })
    }
    fun isDeleted(owner: ArtifactOwner): Boolean {
        val name = ArchiveDisk.owner(owner) + ".deleted"
        return File(deleting, name).isFile || File(deleted, name).isFile
    }
    fun delete(owner: ArtifactOwner): Boolean {
        if (isDeleted(owner)) return false
        ArchiveDisk.write(File(deleting, ArchiveDisk.owner(owner) + ".deleted"), Properties().apply { putOwner(owner) })
        return true
    }
    fun deletionScheduled(file: File) = ArchiveDisk.move(file, File(deleted, file.name))
    fun removeSlot(owner: ArtifactOwner, key: String) {
        ArchiveDisk.remove(slotFile(owner, key))
        slots(owner).delete() // Only succeeds when empty; never recursively discards another reference.
    }
    fun removePublication(owner: ArtifactOwner, id: String, slot: String) {
        ArchiveDisk.remove(publicationFile(owner, id))
        if (readSlot(owner, slot)?.getProperty("id") == id) removeSlot(owner, slot)
        slots(owner).delete()
    }
    fun artifact(p: Properties) = PublishedArtifact(p.getProperty("id"), p.owner(), p.getProperty("destination"),
        ArchivalOperation.valueOf(p.getProperty("operation")), p.getProperty("part").toInt(), p.getProperty("generation").toLong(), p.getProperty("uri"),
        p.getProperty("representation") ?: if (p.getProperty("operation") == ArchivalOperation.ENCODE_AUDIO.name)
            "encoded-${p.getProperty("id")}" else AudioRepresentation.ORIGINAL)
    fun forEachPublished(owner: ArtifactOwner, action: (PublishedArtifact) -> Unit) {
        if (isDeleted(owner)) return
        ArchiveDisk.visit(slots(owner), ".pub") { file ->
            val p = ArchiveDisk.read(file)
            if (p.getProperty("state") == ArchivalState.PUBLISHED.name && !retired(p)) action(artifact(p))
            true
        }
    }
}
