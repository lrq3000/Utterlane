package io.github.lrq3000.utterlane.archive

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.Closeable
import java.util.UUID

data class ArchivalStatus(val queued: Int = 0, val running: Int = 0, val paused: Int = 0,
    val cleanup: Int = 0, val producers: Int = 0, val message: String? = null) {
    val hasWork get() = queued + running + paused + cleanup + producers > 0
}

/** Parent owns journals, history, settings, leases and notification ownership.
 * The single drain coroutine submits descriptors; it never does provider IO. */
class ArchivalCoordinator(private val app: UtterlaneApp, val journal: ArchivalJobStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val worker = ArchiveWorkerClient(app)
    private val mutable = MutableStateFlow(ArchivalStatus())
    val state: StateFlow<ArchivalStatus> = mutable
    private val visible = mutableSetOf<Activity>()
    private val producers = mutableSetOf<String>()
    private var cleanupAllowed = true
    private var supervisor: ArchiveSupervisorService? = null
    private var starting = false
    private var stopping = false
    private var drainJob: Job? = null
    private var inFlight: ArchivalJob? = null
    private var controlGeneration = 0L
    @Volatile private var producerCancellationVersion = 0L
    private val faults = ArchivalFailureBoundary(::haltForFailure)
    private val executions = ArchivalExecution(journal)
    private val encodingFailures = EncodingFailureRecovery(journal, app.recordingHistory)

    inner class Producer internal constructor(val settings: ArchivalSettings, val epoch: Long, private val id: String?) : Closeable {
        val textPlan get() = settings.automaticDestination?.let { ArchivePlan(it, epoch) }
        fun audio(id: String) {
            isolated { app.recordingHistory.planImportEncoding(id, settings.quality, epoch) }
            settings.audioFolder?.let { folder -> isolated { app.recordingHistory.planArchive(id, ArchivePlan(folder, epoch)) } }
        }
        fun encode(id: String) = isolated { app.recordingHistory.planEncoding(id, epoch) }
        fun text(id: String, folder: String? = settings.automaticDestination, retryPaused: Boolean = false): Boolean = folder != null && isolated {
            val submitted = app.transcriptHistory.planArchive(id, ArchivePlan(folder, epoch), retryPaused)
            check(submitted?.state in setOf(ArchivalState.QUEUED, ArchivalState.RUNNING, ArchivalState.READY, ArchivalState.PUBLISHED)) {
                "Markdown is paused or could not be queued; private text is preserved"
            }
        }
        override fun close() { scope.launch {
            id?.let(producers::remove)
            mutable.update { it.copy(producers = producers.size) }
            signal()
        } }
    }

    init {
        scope.launch { journal.failure.filterNotNull().collect { message ->
            mutable.update { it.copy(message = if (faults.paused) "Archival paused: $message" else message) }
        } }
        scope.launch {
            merge(journal.revision, app.recordingHistory.revision, app.transcriptHistory.revision).collect {
                faults.run {
                    refresh() // Independent of a drain waiting on a stuck provider.
                    val running = inFlight
                    if (!stopping && running != null && withContext(Dispatchers.IO) {
                             val orphaned = endedWithoutSpoolClose(running)
                             if (orphaned) {
                                 journal.pendingFallback(running.id, running.generation, "Capture ended without a durable spool close; retry retained fallback")
                                 app.recordingHistory.stopEncoding(running.request.owner.id, "Capture ended with a storage error; original audio is preserved")
                             }
                             orphaned || journal.isDeleted(running.request.owner) || journal.job(running.id)?.state == ArchivalState.CANCELLED
                        } && inFlight === running) {
                        controlGeneration++
                        worker.stopAndKill()
                        drainJob?.cancel()
                    }
                    signal()
                }
            }
        }
        scope.launch {
            consumeArchivalWakes(wake, faults) {
                val turnGeneration = controlGeneration
                val moreMaintenance = if (!stopping) withContext(Dispatchers.IO) { journal.maintain() } else false
                val morePrivateCleanup = if (!stopping) withContext(Dispatchers.IO) { app.recordingHistory.cleanupPublishedEncoding() } else false
                if (faults.paused || turnGeneration != controlGeneration) return@consumeArchivalWakes
                refresh()
                if (faults.paused || turnGeneration != controlGeneration) return@consumeArchivalWakes
                if (!stopping && supervisor != null) {
                    val generation = controlGeneration
                    val task = launch { faults.run { drain(generation) } }
                    drainJob = task
                    task.join()
                    if (drainJob === task) drainJob = null
                } else if (!stopping && visible.isNotEmpty() && (state.value.queued > 0 || state.value.running > 0 ||
                        state.value.producers > 0 || (cleanupAllowed && hasUnattemptedCleanup()))) ensureSupervisor()
                if ((moreMaintenance || morePrivateCleanup) && !stopping) { signal(); yield() }
            }
        }
    }

    /** Call near user-operation start, while UI is still visible, not later when
     * ASR finishes. Failure is an archival status, never an ASR exception. */
    suspend fun producer(manual: Boolean = false, userInitiated: Boolean = false): Producer {
        val requestedBeforeCancellation = producerCancellationVersion
        var registered: Producer? = null
        return try {
            val settings = app.settingsRepository.archival.first()
            val epoch = withContext(Dispatchers.IO) { journal.currentEpoch() }
            withContext(Dispatchers.Main.immediate) {
                while (stopping) delay(10)
                if (requestedBeforeCancellation != producerCancellationVersion)
                    return@withContext Producer(settings, -1, null)
                if (withContext(Dispatchers.IO) { journal.currentEpoch() } != epoch)
                    return@withContext Producer(settings, epoch, null)
                val id = if (manual || settings.enabled) UUID.randomUUID().toString().also(producers::add) else null
                if (id != null) ensureSupervisor(userInitiated, settings.quality.encoding)
                signal()
                Producer(settings, epoch, id).also { registered = it }
            }
        } catch (e: Exception) {
            if (e is CancellationException) { registered?.close(); throw e }
            report(e)
            Producer(ArchivalSettings(), -1, null)
        }
    }

    fun visible(activity: Activity, resumed: Boolean) {
        val wasEmpty = visible.isEmpty()
        if (resumed) visible.add(activity) else visible.remove(activity)
        if (resumed && wasEmpty) retry()
    }

    fun retry() {
        scope.launch {
            faults.retry()
            faults.run {
                cleanupAllowed = true
                mutable.update { it.copy(message = null) }
                withContext(Dispatchers.IO) { journal.retryPaused(); journal.beginCleanupPass() }
                signal()
            }
        }
    }

    private fun ensureSupervisor(userInitiated: Boolean = false, encoding: Boolean = false) {
        if (encoding) supervisor?.enableMediaProcessing()
        if (supervisor != null || starting || stopping || faults.paused) return
        // The microphone entry point may be a visible overlay/IME rather than an
        // Activity. Attempt ownership at that user operation's START; Android is
        // still the authority and denial pauses archival. Completion callbacks
        // and ordinary queue wakeups never acquire this exemption by inference.
        if (visible.isEmpty() && !userInitiated) {
            mutable.update { it.copy(message = "Archival queued. Open Utterlane to continue; private input is preserved.") }
            return
        }
        try {
            starting = true
            ContextCompat.startForegroundService(app, Intent(app, ArchiveSupervisorService::class.java).putExtra("encoding", encoding))
        } catch (e: Exception) {
            starting = false
            faults.fail(e)
        }
    }
    internal fun attached(service: ArchiveSupervisorService) {
        if (faults.paused || stopping) {
            starting = false
            try { service.finish() } catch (error: Exception) { faults.fail(error) }
            return
        }
        starting = false; supervisor = service
        mutable.update { it.copy(message = null) }
        signal()
    }
    internal fun detached(service: ArchiveSupervisorService) {
        if (supervisor !== service) return
        supervisor = null; starting = false
        pauseAndStop("Archival service stopped; open Utterlane or retry to continue")
    }
    internal fun startFailed(error: Exception) {
        starting = false; faults.fail(error)
    }

    fun forceStopAll() { stop(cancel = true, message = null) }
    internal fun pauseAndStop(message: String) { stop(cancel = false, message = message) }
    private fun stop(cancel: Boolean, message: String?) {
        if (stopping) return
        stopping = true
        if (cancel) producerCancellationVersion++
        controlGeneration++
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (cancel) journal.cancelAll()
                    // Queued descriptors already survive the timeout; only the
                    // single executing job needs a new durable pause marker.
                    else journal.pauseExecuting(message.orEmpty())
                    app.recordingHistory.stopActiveEncoders(message ?: "Audio compression was force-stopped; PCM capture continues")
                    inFlight?.takeIf { it.request.operation == ArchivalOperation.ENCODE_AUDIO }?.let {
                        app.recordingHistory.stopEncoding(it.request.owner.id, message ?: "Audio compression was force-stopped; audio is recoverable")
                    }
                }
                // The cancel/pause marker is durable before the process is stopped.
                // Provider work can be hung indefinitely; none is awaited here.
                producers.clear()
                cleanupAllowed = false
                finishSupervisor()
                drainJob?.cancel()
                // One gate replaces a set containing every historical target.
                // Private retirement may continue; provider cleanup awaits retry.
                refresh()
                mutable.update { it.copy(message = message ?: "Archival stopped. Saved audio and recovery sources are preserved; microphone capture and transcription continue independently.") }
            } catch (cancelled: CancellationException) {
                drainJob?.cancel()
                try { finishSupervisor() } catch (cleanup: Exception) { cancelled.addSuppressed(cleanup) }
                throw cancelled
            } catch (e: Exception) {
                if (faults.paused) haltForFailure(e) else faults.fail(e)
            } finally { stopping = false }
        }
    }

    private suspend fun drain(generation: Long) {
        var completed = false
        try {
            withContext(Dispatchers.IO) { app.recordingHistory.initialize(); app.transcriptHistory.initialize() }
            while (!stopping && generation == controlGeneration && supervisor != null) {
                withContext(Dispatchers.IO) { journal.maintain() }
                val recovered = withContext(Dispatchers.IO) { journal.nextReady() }
                if (recovered != null) {
                    publish(recovered)
                    continue
                }
                val claimed = executions.runNext(
                    quiesce = { job -> if (inFlight === job) worker.stopAndKill() },
                    released = { job -> if (inFlight === job) inFlight = null },
                    cleanupFailed = { error -> if (generation == controlGeneration) faults.fail(error) else report(error) }
                ) { owned ->
                    val job = owned.job
                    inFlight = job
                    try {
                        owned.acquireLease {
                            when (job.request.owner.kind) {
                                ArtifactKind.AUDIO -> app.recordingHistory.acquire(job.request.owner.id)
                                ArtifactKind.TRANSCRIPT -> app.transcriptHistory.acquire(job.request.owner.id)
                            }
                        }
                        if (job.phase == ArchivalPhase.PENDING_FALLBACK) {
                            withContext(Dispatchers.IO) { encodingFailures.afterWorkerStopped(job, "Retrying retained audio fallback") }
                            return@runNext
                        }
                        if (withContext(Dispatchers.IO) { endedWithoutSpoolClose(job) })
                            throw ArchiveWorkerFailure("Capture ended without a durable spool close; original audio is preserved", creationStarted = false)
                        if (job.request.operation == ArchivalOperation.ENCODE_AUDIO && withContext(Dispatchers.IO) {
                                !RollingPcmJournal.open(checkNotNull(job.request.source.parentFile)).state.encodingAllowed
                            }) throw ArchiveWorkerFailure("Encoding is stopped; publishing retained audio instead", creationStarted = false)
                        owned.dispatched = true
                        if (job.request.operation == ArchivalOperation.ENCODE_AUDIO) supervisor?.enableMediaProcessing()
                        if (job.request.operation == ArchivalOperation.COPY_AUDIO) withContext(Dispatchers.IO) {
                            app.recordingHistory.preparePart(app.recordingHistory.get(job.request.owner.id), job.request.part)
                        }
                        worker.execute(job) { uri -> withContext(Dispatchers.IO) { journal.target(job.id, job.generation, uri) } }
                        owned.quiescent = true
                        if (withContext(Dispatchers.IO) { journal.ready(job.id, job.generation) }) publish(job)
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        // The parent-only retry already persisted its pending
                        // phase. Let the shared failure boundary pause until the
                        // next Retry rather than rerunning it in this catch.
                        if (job.phase == ArchivalPhase.PENDING_FALLBACK) throw e
                        worker.stopAndKill()
                        owned.quiescent = true
                        withContext(Dispatchers.IO) {
                            if (e is ArchiveWorkerFailure && e.creationStarted == false) journal.notCreated(job.id, job.generation)
                            if (job.request.operation == ArchivalOperation.ENCODE_AUDIO)
                                encodingFailures.afterWorkerStopped(job, e.message ?: "Audio compression failed; PCM is preserved")
                            else journal.pause(job.id, job.generation, e.message ?: "Archival failed; private input is preserved")
                        }
                        report(e)
                    }
                }
                if (!claimed) break
            }
            if (!stopping && generation == controlGeneration && supervisor != null && cleanupAllowed) cleanup(generation)
            completed = true
        } finally {
            // An error in the body goes straight to the shared failure boundary;
            // do not obscure it with more fallible IO from a finally block.
            if (completed && !stopping && !faults.paused && generation == controlGeneration) {
                refresh()
                val pending = withContext(Dispatchers.IO) { journal.summary() }
                if (state.value.queued == 0 && state.value.running == 0 && producers.isEmpty() &&
                    (!cleanupAllowed || (!pending.maintenance && pending.readyCleanup == 0))) finishSupervisor()
            }
        }
    }

    private suspend fun publish(job: ArchivalJob) = withContext(Dispatchers.IO) {
        ArchivalPublication(journal, app.recordingHistory, app.transcriptHistory).finish(job)
    }
    /** A failed EOF metadata write must not leave a worker polling forever. This
     * reads parent capture ownership, not codec progress or model availability. */
    private fun endedWithoutSpoolClose(job: ArchivalJob): Boolean {
        if (job.request.operation != ArchivalOperation.ENCODE_AUDIO || job.phase == ArchivalPhase.PENDING_FALLBACK || journal.isDeleted(job.request.owner)) return false
        val entry = app.recordingHistory.get(job.request.owner.id)
        return entry.rollingAudio?.liveCapture == true && !entry.rollingAudio.complete && entry.status != "active" &&
            !RollingPcmJournal.open(entry.directory).captureClosedOnDisk
    }

    private suspend fun cleanup(generation: Long) {
        while (!stopping && cleanupAllowed && generation == controlGeneration) {
            val target = withContext(Dispatchers.IO) { journal.nextCleanup() } ?: return
            if (hasLeases(target.owner)) {
                withContext(Dispatchers.IO) { journal.deferCleanupForLease(target.key) }
                continue
            }
            try {
                val uri = target.uri ?: worker.find(target.destination, checkNotNull(target.name))?.also {
                    withContext(Dispatchers.IO) { journal.resolvedCleanup(target.key, it) }
                }
                // Unknown creation can outlive a killed worker. An absent match
                // stays on disk, marked attempted for this pass, without a heap set.
                if (uri != null && worker.delete(uri)) withContext(Dispatchers.IO) { journal.completeCleanup(target.key) }
                else if (uri == null && target.destination.startsWith("file:")) withContext(Dispatchers.IO) {
                    // A quiescent codec cannot later create a private target. The
                    // external-provider late-creation ambiguity does not apply.
                    journal.completeCleanup(target.key, absentConfirmed = true)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { worker.stopAndKill(); report(e) }
        }
    }
    private suspend fun hasLeases(owner: ArtifactOwner) = withContext(Dispatchers.IO) {
        when (owner.kind) {
            ArtifactKind.AUDIO -> app.recordingHistory.hasLeases(owner.id)
            ArtifactKind.TRANSCRIPT -> app.transcriptHistory.hasLeases(owner.id)
        }
    }
    private suspend fun hasUnattemptedCleanup() = withContext(Dispatchers.IO) { journal.summary().readyCleanup > 0 }
    private suspend fun refresh() {
        val counts = withContext(Dispatchers.IO) { journal.summary() }
        mutable.update { old -> ArchivalStatus(counts.queued, counts.running, counts.paused, counts.cleanup, producers.size, old.message) }
        supervisor?.update(state.value)
    }
    private fun finishSupervisor() {
        val service = supervisor
        supervisor = null; starting = false
        try { worker.stopAndKill() } finally { service?.finish() }
    }
    private fun haltForFailure(error: Exception) {
        controlGeneration++
        cleanupAllowed = false
        drainJob?.cancel()
        // The reporter is not allowed to become another uncaught failure path.
        try { finishSupervisor() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        report(error)
        mutable.update { it.copy(running = 0, message = "Archival paused: ${error.message ?: error.javaClass.simpleName}. Private input and pending work are preserved.") }
    }
    private fun signal() { wake.trySend(Unit) }
    private fun report(error: Exception) {
        Log.e("Archival", "Archival operation failed; private input is preserved", error)
        mutable.update { it.copy(message = error.message ?: "Archival failed; private input is preserved") }
    }
    /** Also used from publication hooks on IO; optional archival cannot fail ASR. */
    private fun isolated(action: () -> Unit): Boolean {
        return try { action(); true } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e); false }
    }
}
