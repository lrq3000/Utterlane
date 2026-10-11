package io.github.lrq3000.utterlane.archive

import java.io.Closeable
import java.io.File
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Parent-only disk ledger. Only unfinished execution descriptors enter the
 * bounded LRU. Published URI authority and owner deletion fences live in a
 * direct-addressed catalog; pending targets have individual records/cursors. */
class ArchivalJobStore(private val root: File) : Closeable {
    companion object { const val CACHE_LIMIT = 32 }
    private val work = File(root, "work")
    private val indexes = File(root, "index")
    private val replacements = File(root, "replacements")
    private val catalog = ArchiveCatalog(root)
    private val targets = ArchiveTargets(root, indexes)
    private val cache = object : LinkedHashMap<String, ArchivalJob>(CACHE_LIMIT, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArchivalJob>?) = size > CACHE_LIMIT
    }
    private var initialized = false
    private var closed = false
    private var epoch = 0L
    private var pass = 0L
    private var queued = 0
    private var running = 0
    private var ready = 0
    private var paused = 0
    private var executionRecords = 0
    private var pendingOwners = 0
    private var pendingReplacements = 0
    private var replacingFile: File? = null
    private var replacingSlots: ArchiveDisk.Cursor? = null
    private var replacingOwner: ArtifactOwner? = null
    private var replacingRepresentation: String? = null
    private var execution: Pair<String, Long>? = null
    private var sweep: ArchiveDisk.Cursor? = null
    private var retrySweep: ArchiveDisk.Cursor? = null
    private var cleanupSweep: ArchiveTargets.Cursor? = null
    private var leaseWakeups = 0
    private var leaseWakeFile: File? = null
    private var leaseWakeTargets: ArchiveTargets.OwnerCursor? = null
    private var deletingFile: File? = null
    private var deletingOwner: ArtifactOwner? = null
    private var deletingJobs: ArchiveDisk.Cursor? = null
    private var deletingSlots: ArchiveDisk.Cursor? = null
    private var jobReads = 0L
    private var indexVisits = 0L
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    private val failures = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = failures
    fun reportFailure(error: Exception) { failures.value = error.message ?: "Cannot queue archival; private history is preserved" }

    private fun jobFile(id: String): File {
        require(id.isNotEmpty() && File(id).name == id && id != "." && id != ".." && '/' !in id && '\\' !in id)
        return File(work, "$id.job")
    }
    private fun queue(state: ArchivalState, epoch: Long = this.epoch) = File(indexes, "${state.name}-$epoch")
    private fun ownerJobs(owner: ArtifactOwner) = File(indexes, "owner-jobs/${ArchiveDisk.owner(owner)}")
    private fun stateMarker(job: ArchivalJob) = File(queue(job.state, job.epoch), "${job.id}.ref")
    private fun saveControl(nextEpoch: Long = epoch, nextPass: Long = pass) = ArchiveDisk.write(File(root, "control.properties"), Properties().apply {
        setProperty("epoch", nextEpoch.toString()); setProperty("pass", nextPass.toString())
    })

    @Synchronized private fun initialize() {
        check(!closed) { "Archival journal is closed" }
        if (initialized) return
        check(root.mkdirs() || root.isDirectory)
        epoch = 0; pass = 0
        val control = File(root, "control.properties")
        if (control.isFile) ArchiveDisk.read(control).let { epoch = it.getProperty("epoch").toLong(); pass = it.getProperty("pass", "0").toLong() }
        migrateLegacy()
        // Runtime leases disappeared with the old parent. A new cleanup pass may
        // inspect pending intents, but never enumerates published owners/tombstones.
        pass++; saveControl()
        ArchiveDisk.clearDerived(indexes)
        cache.clear(); queued = 0; running = 0; ready = 0; paused = 0; executionRecords = 0; pendingOwners = 0; leaseWakeups = 0
        ArchiveDisk.visit(work, ".job") { file ->
            val raw = readJob(file)
            val normalized = normalize(raw, restart = true, bootstrap = true)
            if (normalized != raw) ArchiveDisk.write(file, ArchiveRecords.job(normalized))
            val slot = catalog.read(normalized.request)
            if (normalized.state !in setOf(ArchivalState.CANCELLED, ArchivalState.PUBLISHED) &&
                (slot == null || slot.getProperty("state") == ArchivalState.CANCELLED.name ||
                    (slot.getProperty("state") != ArchivalState.PUBLISHED.name && normalized.epoch > slot.getProperty("epoch").toLong())))
                catalog.write(normalized)
            if (!retireDisk(normalized)) { executionRecords++; indexJob(normalized) }
            true
        }
        targets.rebuild(isEligible = { ref -> eligible(ref) }, redundant = { ref -> protectsPublished(ref) })
        // A committed publication can drop its redundant current target during
        // rebuild. Retire only the affected terminal descriptors, not all history.
        ArchiveDisk.visit(work, ".job") { file ->
            val job = load(file.name.removeSuffix(".job")) ?: return@visit true
            retire(job); true
        }
        ArchiveDisk.visit(catalog.deleting, ".deleted") { pendingOwners++; true }
        pendingReplacements = 0
        ArchiveDisk.visit(replacements, ".replace") { pendingReplacements++; true }
        initialized = true
    }

    private fun readJob(file: File): ArchivalJob { jobReads++; return ArchiveRecords.job(file.name.removeSuffix(".job"), ArchiveDisk.read(file)) }
    private fun load(id: String): ArchivalJob? = cache[id] ?: jobFile(id).takeIf { it.isFile }?.let(::readJob)?.also { cache[id] = it }
    private fun contribution(job: ArchivalJob, delta: Int) {
        if (job.epoch != epoch) return
        when (job.state) {
            ArchivalState.QUEUED -> queued += delta
            ArchivalState.RUNNING -> running += delta
            ArchivalState.READY -> ready += delta
            ArchivalState.PAUSED -> paused += delta
            else -> Unit
        }
    }
    private fun indexJob(job: ArchivalJob) {
        contribution(job, 1)
        if (job.state in setOf(ArchivalState.QUEUED, ArchivalState.RUNNING, ArchivalState.READY, ArchivalState.PAUSED)) ArchiveDisk.marker(stateMarker(job))
        if (!catalog.isDeleted(job.request.owner)) ArchiveDisk.marker(File(ownerJobs(job.request.owner), "${job.id}.ref"))
    }
    private fun unindexJob(job: ArchivalJob) {
        contribution(job, -1)
        ArchiveDisk.remove(stateMarker(job)); stateMarker(job).parentFile?.delete()
        ArchiveDisk.remove(File(ownerJobs(job.request.owner), "${job.id}.ref")); ownerJobs(job.request.owner).delete()
    }
    private fun put(job: ArchivalJob) = changing {
        val previous = load(job.id)
        ArchiveDisk.write(jobFile(job.id), ArchiveRecords.job(job))
        if (previous != null) unindexJob(previous) else executionRecords++
        cache[job.id] = job
        indexJob(job)
        if (job.state == ArchivalState.CANCELLED) {
            val slot = catalog.read(job.request)
            if (slot?.getProperty("id") == job.id && slot.getProperty("state") != ArchivalState.PUBLISHED.name)
                catalog.write(job, ArchivalState.CANCELLED)
        }
        targets.get(targets.canonical(job.id, job.generation))?.let { targets.eligible(it, eligible(it)) }
        changes.value++
    }
    private fun reference(job: ArchivalJob, uri: String? = null, name: String? = null, generation: Long = job.generation, extra: Boolean = false) =
        ArchivalCleanup(job.id, ArchiveDisk.key(if (extra) "${job.id}:$generation:$uri" else "${job.id}:$generation"), generation,
            job.request.owner, job.request.destination, ArchiveDisk.slot(job.request), uri, name)

    private fun ensureUri(job: ArchivalJob, bootstrap: Boolean = false) {
        val uri = job.uri ?: return
        val old = targets.get(targets.canonical(job.id, job.generation))
        val ref = when {
            old == null -> reference(job, uri = uri)
            old.uri == null || old.uri == uri -> old.copy(uri = uri, name = null)
            else -> reference(job, uri = uri, extra = true)
        }
        if (bootstrap) targets.writeUnindexed(ref) else targets.write(ref, eligible(ref))
    }
    private fun normalize(raw: ArchivalJob, restart: Boolean = false, bootstrap: Boolean = false): ArchivalJob {
        val publication = catalog.publication(raw.request.owner, raw.id)
        var job = if (publication != null) raw.copy(state = ArchivalState.PUBLISHED, uri = publication.getProperty("uri"), error = null) else raw
        if (publication != null && !catalog.isDeleted(job.request.owner)) catalog.repairPublicationIndex(job)
        job = when {
            publication == null && !catalog.isDeleted(job.request.owner) &&
                (job.request.operation == ArchivalOperation.COPY_AUDIO && job.request.representation != catalog.activeRepresentation(job.request.owner) ||
                    job.phase == ArchivalPhase.PENDING_FALLBACK && job.request.sourceRepresentation != catalog.activeRepresentation(job.request.owner)) -> {
                ensureUri(job, bootstrap); job.copy(state = ArchivalState.CANCELLED, uri = null, error = null)
            }
            catalog.isDeleted(job.request.owner) || (job.epoch < epoch && job.state != ArchivalState.PUBLISHED) -> {
                ensureUri(job, bootstrap); job.copy(state = ArchivalState.CANCELLED, uri = null, error = null)
            }
            restart && job.state == ArchivalState.RUNNING && execution?.first != job.id -> {
                ensureUri(job, bootstrap); job.copy(state = ArchivalState.PAUSED, uri = null, error = "Archival was interrupted; retry when ready")
            }
            else -> job
        }
        return job
    }
    private fun protectsPublished(ref: ArchivalCleanup): Boolean {
        if (ref.uri == null || catalog.isDeleted(ref.owner)) return false
        val slot = catalog.publication(ref.owner, ref.jobId)
        return slot?.getProperty("state") == ArchivalState.PUBLISHED.name && !catalog.retired(slot) && slot.getProperty("uri") == ref.uri
    }
    private fun eligible(ref: ArchivalCleanup): Boolean {
        // The single execution/READY gate is enforced at dequeue, not by touching
        // every older target whenever a job changes state.
        return ref.lastPass != pass && !ref.waitingForLease
    }

    @Synchronized fun job(id: String): ArchivalJob? {
        initialize()
        val raw = load(id) ?: return null
        val job = normalize(raw)
        if (job != raw) put(job)
        return if (retire(job)) null else job
    }
    @Synchronized fun currentEpoch(): Long { initialize(); return epoch }
    @Synchronized fun isDeleted(owner: ArtifactOwner): Boolean { initialize(); return catalog.isDeleted(owner) }
    @Synchronized fun forEachPublished(owner: ArtifactOwner, action: (PublishedArtifact) -> Unit) { initialize(); catalog.forEachPublished(owner, action) }
    @Synchronized fun activeRepresentation(owner: ArtifactOwner): String { initialize(); return catalog.activeRepresentation(owner) }
    /** Only after the replacement has reached its selected destination and original
     * processing/read obligations are settled. Publication records remain until
     * exact-URI cleanup completes; the owner itself is never tombstoned here. */
    @Synchronized fun retireSupersededRepresentations(owner: ArtifactOwner, keep: String) {
        initialize()
        if (catalog.isDeleted(owner) || catalog.activeRepresentation(owner) != keep) return
        if (catalog.replacementSettled(owner, keep)) return
        val file = File(replacements, "${ArchiveDisk.owner(owner)}.replace")
        val exists = file.isFile
        if (exists && ArchiveDisk.read(file).getProperty("keep") == keep) return
        ArchiveDisk.write(file, Properties().apply { putOwner(owner); setProperty("keep", keep) })
        if (!exists) pendingReplacements++
        changes.value++
    }
    @Synchronized fun summary(): ArchiveSummary {
        initialize()
        return ArchiveSummary(queued, running + ready, paused, targets.count, targets.readyCount, hasMaintenance())
    }
    @Synchronized fun storageDiagnostics(): ArchiveStorageDiagnostics {
        initialize(); return ArchiveStorageDiagnostics(cache.size, 0, executionRecords, jobReads, indexVisits + targets.indexVisits)
    }

    @Synchronized fun enqueue(request: ArchivalRequest, producerEpoch: Long? = null, retryPaused: Boolean = false): ArchiveSubmission {
        initialize()
        if (producerEpoch != null && producerEpoch != epoch) throw ArchivalCancelledException()
        check(!catalog.isDeleted(request.owner)) { "The archival owner was deleted" }
        if (request.owner.kind == ArtifactKind.AUDIO && request.operation == ArchivalOperation.COPY_AUDIO &&
            request.representation != catalog.activeRepresentation(request.owner)) throw ArchivalCancelledException()
        require(if (request.operation == ArchivalOperation.ENCODE_AUDIO)
            request.destination == checkNotNull(request.source.parentFile).toURI().toString()
        else request.destination.startsWith("content://"))
        require(request.displayName.length in 1..180 && request.displayName.none { it == '/' || it == '\\' || it.code < 32 })
        val slot = catalog.read(request)
        if (slot != null) {
            val id = slot.getProperty("id")
            if (slot.getProperty("state") == ArchivalState.PUBLISHED.name) return ArchiveSubmission(id, ArchivalState.PUBLISHED)
            val previous = job(id)
            // Resolving the descriptor can repair a publication commit or a
            // cancellation fence. Never make a new decision from the old pointer.
            val repaired = catalog.read(request)
            if (repaired?.getProperty("state") == ArchivalState.PUBLISHED.name)
                return ArchiveSubmission(repaired.getProperty("id"), ArchivalState.PUBLISHED)
            if (previous != null && previous.state != ArchivalState.CANCELLED) {
                if (retryPaused) retry(id)
                return ArchiveSubmission(id, checkNotNull(job(id)).state)
            }
            // A same-epoch individual cancellation needs a compact fence after
            // retiring its job; an old automatic producer cannot recreate it.
            if (!retryPaused && repaired?.getProperty("state") == ArchivalState.CANCELLED.name && repaired.getProperty("epoch").toLong() == epoch)
                return ArchiveSubmission(id, ArchivalState.CANCELLED)
        }
        val job = ArchivalJob(UUID.randomUUID().toString(), request, epoch)
        // Job first: startup repairs a missing slot after a crash in this gap.
        changing { put(job); catalog.write(job) }
        return ArchiveSubmission(job.id, job.state)
    }
    private fun first(state: ArchivalState): ArchivalJob? {
        while (true) {
            val file = ArchiveDisk.first(queue(state), ".ref") ?: return null
            indexVisits++
            val found = job(file.name.removeSuffix(".ref"))
            if (found?.state == state && found.epoch == epoch) return found
            ArchiveDisk.remove(file)
        }
    }
    @Synchronized fun nextReady(): ArchivalJob? { initialize(); return if (ready == 0) null else first(ArchivalState.READY) }
    @Synchronized fun claim(): ArchivalJob? {
        initialize()
        if (execution != null || ready > 0 || queued == 0) return null
        val previous = first(ArchivalState.QUEUED) ?: return null
        val job = previous.copy(state = ArchivalState.RUNNING, generation = previous.generation + 1, uri = null, error = null)
        changing {
            put(job)
            if (job.phase == ArchivalPhase.PRIMARY) {
                val intent = reference(job, name = job.targetName)
                targets.write(intent, false)
            }
        }
        execution = job.id to job.generation
        return job
    }
    @Synchronized fun target(id: String, generation: Long, uri: String): Boolean {
        initialize()
        val job = job(id) ?: return false // Retired records have no live producer/intent.
        require(if (job.request.operation == ArchivalOperation.ENCODE_AUDIO)
            uri == File(job.request.source.parentFile, job.targetName).toURI().toString()
        else uri.startsWith("content://"))
        val accepted = live(job, generation) && job.state == ArchivalState.RUNNING && job.phase == ArchivalPhase.PRIMARY
        val current = targets.get(targets.canonical(id, generation))
        if (current?.uri != null && current.uri != uri) {
            val extra = reference(job, uri = current.uri, generation = generation, extra = true)
            targets.write(extra, eligible(extra))
        }
        val ref = reference(job, uri = uri, generation = generation)
        if (!protectsPublished(ref)) targets.write(ref, eligible(ref))
        if (accepted) put(job.copy(uri = uri)) else changes.value++
        return accepted
    }
    @Synchronized fun notCreated(id: String, generation: Long) {
        initialize()
        val job = job(id) ?: return
        val ref = targets.get(targets.canonical(id, generation)) ?: return
        if (job.generation == generation && ref.uri == null) { targets.remove(ref); changes.value++; retire(job) }
    }
    @Synchronized fun ready(id: String, generation: Long): Boolean {
        initialize()
        val job = job(id) ?: return false
        if (!live(job, generation) || job.state != ArchivalState.RUNNING || job.uri == null || job.phase != ArchivalPhase.PRIMARY) return false
        if (execution == (id to generation)) execution = null // DONE acknowledged writer close.
        put(job.copy(state = ArchivalState.READY)); return true
    }
    @Synchronized fun releaseExecution(id: String, generation: Long) {
        // This runtime reservation must be released even when private metadata IO
        // is failing. Only its exact owner may do so; old cleanup cannot release
        // a later generation. Durable recovery is repaired after IO returns.
        if (execution == (id to generation)) execution = null
        initialize()
        targets.get(targets.canonical(id, generation))?.let { targets.eligible(it, eligible(it)) }
        job(id)?.let(::retire)
        changes.value++
    }
    /** Finalization of this reservation only. A queued explicit retry, READY
     * publication, or newer generation is not an interrupted running attempt. */
    @Synchronized fun interruptExecution(id: String, generation: Long) {
        if (execution != (id to generation)) return
        initialize()
        val owned = job(id) ?: return
        if (owned.state == ArchivalState.RUNNING && owned.generation == generation)
            pause(id, generation, "Archival interrupted; private input is preserved")
    }
    @Synchronized fun publish(id: String, generation: Long, action: (ArchivalJob) -> Unit): Boolean {
        initialize()
        val job = job(id) ?: return false
        if (!live(job, generation) || job.state != ArchivalState.READY) return false
        val published = job.copy(state = ArchivalState.PUBLISHED, error = null)
        catalog.write(published) // Single durable URI authority / commit point.
        put(published)
        targets.get(targets.canonical(id, generation))?.takeIf { it.uri == published.uri }?.let(targets::remove)
        action(published)
        retire(published)
        return true
    }
    @Synchronized fun pause(id: String, generation: Long, message: String) {
        initialize()
        val job = job(id) ?: return
        if (live(job, generation) && job.state in setOf(ArchivalState.RUNNING, ArchivalState.QUEUED)) {
            ensureUri(job)
            put(job.copy(state = ArchivalState.PAUSED, uri = null, error = message.take(1024)))
        }
    }
    /** Commit this before fallible PCM fencing/closure/queueing. Existing cleanup
     * intents still belong to the stopped codec generation; retries create none. */
    @Synchronized fun pendingFallback(id: String, generation: Long, message: String): ArchivalJob? {
        initialize()
        val job = job(id) ?: return null
        if (!live(job, generation) || job.request.operation != ArchivalOperation.ENCODE_AUDIO ||
            job.state !in setOf(ArchivalState.RUNNING, ArchivalState.PAUSED)) return null
        ensureUri(job)
        val pending = job.copy(phase = ArchivalPhase.PENDING_FALLBACK, state = ArchivalState.PAUSED,
            uri = null, error = message.take(1024))
        if (job != pending) put(pending)
        return pending
    }
    @Synchronized fun completeFallback(id: String, generation: Long) {
        initialize()
        val job = job(id) ?: return
        if (live(job, generation) && job.phase == ArchivalPhase.PENDING_FALLBACK) cancel(id)
    }
    /** Normal capture finish can acknowledge its earlier failure after closure and
     * all COPY descriptors commit. Resolve one dedup slot, not an owner-wide scan. */
    @Synchronized fun completeFallback(request: ArchivalRequest) {
        initialize()
        val id = catalog.read(request)?.getProperty("id") ?: return
        val job = job(id) ?: return
        if (execution?.first != id && job.request.sourceRepresentation == request.sourceRepresentation)
            completeFallback(id, job.generation)
    }
    @Synchronized fun retry(id: String, oncePerPass: Boolean = false) {
        initialize()
        val job = job(id) ?: return
        if (job.state == ArchivalState.PAUSED && job.epoch == epoch && !catalog.isDeleted(job.request.owner) &&
            (!oncePerPass || job.retryPass != pass)) put(job.copy(state = ArchivalState.QUEUED, error = null, retryPass = pass))
    }
    @Synchronized fun retryPaused() {
        initialize()
        retrySweep?.close(); retrySweep = ArchiveDisk.Cursor(queue(ArchivalState.PAUSED), ".ref")
        changes.value++
    }
    @Synchronized fun pauseExecuting(message: String) {
        initialize()
        execution?.let { pause(it.first, it.second, message) }
    }
    @Synchronized fun cancelAll() {
        initialize()
        val active = execution?.first ?: nextReady()?.id
        val next = epoch + 1
        saveControl(nextEpoch = next); epoch = next
        cache.clear(); queued = 0; running = 0; ready = 0; paused = 0
        active?.let(::job) // At most one descriptor, never a sweep before worker kill.
        sweep?.close(); sweep = ArchiveDisk.Cursor(work, ".job")
        changes.value++
    }
    @Synchronized fun cancel(id: String) {
        initialize()
        val job = job(id)?.takeUnless { it.state == ArchivalState.PUBLISHED } ?: return
        ensureUri(job)
        val cancelled = job.copy(state = ArchivalState.CANCELLED, uri = null, error = null)
        put(cancelled); retire(cancelled)
    }
    @Synchronized fun deleteOwner(owner: ArtifactOwner) {
        initialize()
        if (catalog.delete(owner)) pendingOwners++
        cache.entries.removeAll { it.value.request.owner == owner }
        changes.value++
    }
    private fun live(job: ArchivalJob, generation: Long) = job.epoch == epoch && job.generation == generation &&
        !catalog.isDeleted(job.request.owner) && job.state != ArchivalState.CANCELLED && when (job.request.operation) {
            ArchivalOperation.COPY_AUDIO -> job.request.representation == catalog.activeRepresentation(job.request.owner)
            ArchivalOperation.ENCODE_AUDIO -> job.request.sourceRepresentation == catalog.activeRepresentation(job.request.owner)
            else -> true
        }

    /** Terminal execution metadata may go only after all intents/targets settle
     * and the worker is quiescent. Published URI slots and deletion fences remain. */
    private fun retireDisk(job: ArchivalJob): Boolean {
        if (!jobFile(job.id).isFile) return false
        if (job.state !in setOf(ArchivalState.PUBLISHED, ArchivalState.CANCELLED) || execution?.first == job.id || targets.hasJob(job.id)) return false
        val slot = catalog.read(job.request)
        if (job.state == ArchivalState.CANCELLED && slot?.getProperty("id") == job.id) {
            if (catalog.isDeleted(job.request.owner) || job.epoch < epoch) catalog.removeSlot(job.request.owner, ArchiveDisk.slot(job.request))
            else catalog.write(job, ArchivalState.CANCELLED)
        }
        ArchiveDisk.remove(jobFile(job.id))
        return true
    }
    private fun retire(job: ArchivalJob): Boolean {
        if (!retireDisk(job)) return false
        unindexJob(job); cache.remove(job.id); executionRecords--
        changes.value++
        return true
    }

    /** Bounded private maintenance, called between descriptors/on startup/retry.
     * Only unsettled work and owners with a pending deletion are traversed. */
    @Synchronized fun maintain(budget: Int = 32): Boolean = changing {
        initialize(); require(budget in 1..128)
        var left = budget
        while (left > 0 && sweep != null) {
            val file = sweep!!.next()
            if (file == null) { sweep?.close(); sweep = null; break }
            job(file.name.removeSuffix(".job")); left--
        }
        while (left > 0 && retrySweep != null) {
            val file = retrySweep!!.next()
            if (file == null) { retrySweep?.close(); retrySweep = null; break }
            retry(file.name.removeSuffix(".ref"), oncePerPass = true); left--
        }
        while (left > 0 && cleanupSweep != null) {
            val ref = cleanupSweep!!.next()
            if (ref == null) { cleanupSweep?.close(); cleanupSweep = null; break }
            val reset = ref.copy(waitingForLease = false)
            if (ref.waitingForLease) targets.write(reset, eligible(reset)) else targets.eligible(ref, eligible(ref))
            left--
        }
        while (left > 0 && leaseWakeups > 0) {
            if (leaseWakeFile == null) {
                leaseWakeFile = ArchiveDisk.first(File(indexes, "lease-wakes"), ".wake") ?: break
                leaseWakeTargets = targets.ownerCursor(ArchiveDisk.read(leaseWakeFile!!).owner())
            }
            val ref = leaseWakeTargets?.next()
            if (ref != null) {
                if (ref.waitingForLease) {
                    val next = ref.copy(waitingForLease = false, lastPass = -1)
                    targets.write(next, eligible(next))
                }
                left--; continue
            }
            leaseWakeTargets?.close(); leaseWakeTargets = null
            ArchiveDisk.remove(leaseWakeFile!!); leaseWakeFile = null
            leaseWakeups--; left--
        }
        while (left > 0 && pendingOwners > 0) {
            if (deletingFile == null) {
                deletingFile = ArchiveDisk.first(catalog.deleting, ".deleted") ?: break
                deletingOwner = ArchiveDisk.read(deletingFile!!).owner()
                deletingJobs = ArchiveDisk.Cursor(ownerJobs(deletingOwner!!), ".ref")
                deletingSlots = ArchiveDisk.Cursor(catalog.slots(deletingOwner!!), "")
            }
            val jobMarker = deletingJobs?.next()
            if (jobMarker != null) {
                job(jobMarker.name.removeSuffix(".ref"))?.let { cancel(it.id) }
                ArchiveDisk.remove(jobMarker); left--; continue
            }
            val slotFile = deletingSlots?.next()
            if (slotFile != null) {
                if (slotFile.name.endsWith(".pub")) {
                    val slot = ArchiveDisk.read(slotFile)
                    val ref = catalog.artifact(slot)
                    val key = targets.canonical(ref.id, ref.generation)
                    if (targets.get(key) == null) targets.write(ArchivalCleanup(ref.id, key.substringAfter('~'), ref.generation,
                        ref.owner, ref.destination, slot.getProperty("slot"), uri = ref.uri), true)
                } else ArchiveDisk.remove(slotFile)
                left--; continue
            }
            deletingJobs?.close(); deletingSlots?.close()
            catalog.deletionScheduled(deletingFile!!)
            deletingFile = null; deletingOwner = null; deletingJobs = null; deletingSlots = null
            pendingOwners--; left--; changes.value++
        }
        while (left > 0 && pendingReplacements > 0) {
            if (replacingFile == null) {
                replacingFile = ArchiveDisk.first(replacements, ".replace") ?: break
                val plan = ArchiveDisk.read(replacingFile!!)
                replacingOwner = plan.owner(); replacingRepresentation = plan.getProperty("keep")
                replacingSlots = ArchiveDisk.Cursor(catalog.slots(replacingOwner!!), ".pub")
            }
            val owner = replacingOwner!!
            val keep = replacingRepresentation!!
            val file = if (!catalog.isDeleted(owner) && catalog.activeRepresentation(owner) == keep) replacingSlots?.next() else null
            if (file != null) {
                val publication = ArchiveDisk.read(file)
                val ref = catalog.artifact(publication)
                if (ref.representation != keep) {
                    val key = targets.canonical(ref.id, ref.generation)
                    if (targets.get(key) == null) targets.write(ArchivalCleanup(ref.id, key.substringAfter('~'), ref.generation,
                        ref.owner, ref.destination, publication.getProperty("slot"), uri = ref.uri), true)
                    // Intent first; if interrupted before this mark, the pending
                    // pass recreates any target dismissed by protectsPublished.
                    catalog.retirePublication(file, publication)
                }
                left--; continue
            }
            replacingSlots?.close(); replacingSlots = null
            // A newer request can replace this owner's pending plan between
            // bounded passes. Never remove that newer intent with an old cursor.
            if (ArchiveDisk.read(replacingFile!!).getProperty("keep") == keep) {
                catalog.replacementSettled(owner, keep, settle = true)
                ArchiveDisk.remove(replacingFile!!); pendingReplacements--
            }
            replacingFile = null; replacingOwner = null; replacingRepresentation = null; left--; changes.value++
        }
        hasMaintenance()
    }

    @Synchronized fun beginCleanupPass() {
        initialize()
        val next = pass + 1
        saveControl(nextPass = next); pass = next
        cleanupSweep?.close(); cleanupSweep = targets.cursor()
        changes.value++
    }
    @Synchronized fun nextCleanup(): ArchivalCleanup? {
        initialize()
        if (execution != null || ready > 0) return null
        while (targets.readyCount > 0) {
            val ref = targets.firstReady() ?: return null
            if (protectsPublished(ref)) { targets.remove(ref); load(ref.jobId)?.let(::retire); continue }
            if (!eligible(ref)) { targets.eligible(ref, false); continue }
            val attempted = ref.copy(lastPass = pass)
            targets.write(attempted, false)
            return attempted
        }
        return null
    }
    @Synchronized fun cleanup(key: String): ArchivalCleanup? { initialize(); return targets.get(key) }
    @Synchronized fun canClean(key: String): Boolean {
        initialize()
        val ref = targets.get(key) ?: return false
        return execution == null && ready == 0 && !protectsPublished(ref)
    }
    @Synchronized fun forEachCleanup(jobId: String, action: (ArchivalCleanup) -> Unit) { initialize(); targets.forJob(jobId, action) }
    @Synchronized fun deferCleanupForLease(key: String) {
        initialize(); targets.get(key)?.let { targets.write(it.copy(waitingForLease = true), false) }
    }
    @Synchronized fun readerReleased(owner: ArtifactOwner) {
        // This is only a scheduling hint. Failure must never escape a primary
        // recording/transcript lease close or turn a successful read into failure.
        try {
            initialize()
            if (!targets.hasOwner(owner)) return
            val wake = File(indexes, "lease-wakes/${ArchiveDisk.owner(owner)}.wake")
            if (!wake.isFile) {
                ArchiveDisk.write(wake, Properties().apply { putOwner(owner) })
                leaseWakeups++
            }
            changes.value++
        } catch (error: Exception) { reportFailure(error) }
    }
    @Synchronized fun resolvedCleanup(key: String, uri: String) {
        initialize()
        val ref = targets.get(key) ?: return
        require(if (ref.destination.startsWith("file:")) ref.name != null &&
            uri == File(File(java.net.URI(ref.destination)), ref.name).toURI().toString()
        else uri.startsWith("content://"))
        targets.write(ref.copy(uri = uri, name = null), false); changes.value++
    }
    @Synchronized fun completeCleanup(key: String, absentConfirmed: Boolean = false) {
        initialize()
        val ref = targets.get(key) ?: return
        check(execution?.first != ref.jobId) { "Worker must quiesce before cleanup" }
        check(ref.uri != null || absentConfirmed) { "An unknown creation may still complete" }
        val slot = catalog.publication(ref.owner, ref.jobId)
        if (slot != null && (catalog.isDeleted(ref.owner) || catalog.retired(slot)) && slot.getProperty("state") == ArchivalState.PUBLISHED.name && slot.getProperty("uri") == ref.uri)
            catalog.removePublication(ref.owner, ref.jobId, ref.slot)
        targets.remove(ref)
        load(ref.jobId)?.let { val normalized = normalize(it); if (normalized != it) put(normalized); retire(normalized) }
        changes.value++
    }

    private fun migrateLegacy() {
        val version = File(root, "layout-v2.properties")
        if (version.isFile) return
        ArchiveDisk.visit(root, ".deleted") { file ->
            val owner = ArchiveDisk.read(file).owner()
            catalog.delete(owner); ArchiveDisk.remove(file); true
        }
        ArchiveDisk.visit(root, ".job") { file ->
            val p = Properties()
            ArchiveDisk.visitLegacyProperties(file) { key, value ->
                if (key in ArchiveRecords.jobKeys) p.setProperty(key, value)
            }
            val job = ArchiveRecords.job(file.name.removeSuffix(".job"), p)
            if (!jobFile(job.id).exists()) ArchiveDisk.write(jobFile(job.id), ArchiveRecords.job(job))
            // Idempotent target files precede retiring the legacy descriptor.
            ArchiveDisk.visitLegacyProperties(file) { key, value ->
                val ref = when {
                    key.startsWith("cleanup.") && key != "cleanup.count" -> reference(job, uri = value, extra = true)
                    key.startsWith("unresolved.") && key != "unresolved.count" ->
                        reference(job, name = value).copy(id = ArchiveDisk.key("${job.id}:name:$value"))
                    else -> null
                }
                if (ref != null && targets.get(ref.key) == null) targets.writeUnindexed(ref)
            }
            val slot = catalog.read(job.request)
            if (job.state == ArchivalState.PUBLISHED || slot == null || (slot.getProperty("state") != ArchivalState.PUBLISHED.name &&
                    job.epoch > slot.getProperty("epoch").toLong())) catalog.write(job)
            ArchiveDisk.remove(file); true
        }
        ArchiveDisk.write(version, Properties().apply { setProperty("version", "2") })
    }
    @Synchronized override fun close() {
        closed = true
        closeCursors()
    }
    private fun closeCursors() {
        sweep?.close(); sweep = null
        retrySweep?.close(); retrySweep = null
        cleanupSweep?.close(); cleanupSweep = null
        leaseWakeTargets?.close(); leaseWakeTargets = null; leaseWakeFile = null
        deletingJobs?.close(); deletingSlots?.close(); deletingJobs = null; deletingSlots = null
        deletingFile = null; deletingOwner = null
        replacingSlots?.close(); replacingSlots = null; replacingFile = null; replacingOwner = null; replacingRepresentation = null
    }
    private fun hasMaintenance() = sweep != null || retrySweep != null || cleanupSweep != null || pendingOwners > 0 || pendingReplacements > 0 || leaseWakeups > 0
    private inline fun <T> changing(action: () -> T): T = try { action() } catch (error: Exception) {
        // Authority may have reached disk before a disposable index failed. The
        // next access rebuilds only unsettled indexes; it cannot trust old counts.
        closeCursors()
        initialized = false; cache.clear()
        throw error
    }
}
