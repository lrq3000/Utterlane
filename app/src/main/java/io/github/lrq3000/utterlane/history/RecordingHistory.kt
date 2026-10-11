package io.github.lrq3000.utterlane.history

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.UUID
import io.github.lrq3000.utterlane.audio.MicrophoneOptions
import io.github.lrq3000.utterlane.archive.*

data class HistoryEntry(val id: String, val directory: File, val started: Long, val reference: Long, val samples: Long, val status: String,
    val needsRecovery: Boolean = false, val temporary: Boolean = false,
    val pinned: Boolean = false, val holdForLaunch: String? = null,
    val sourceName: String? = null, val mimeType: String = "audio/wav", val importedDurationMs: Long = 0,
    val failureMessage: String? = null, val failureKind: String? = null, val speakerLabels: Boolean = false,
    val recovered: Boolean = false,
    val importRetention: ImportRetentionChoice? = null, val importMetadata: AudioImportMetadata? = null,
    val processingAttempts: Set<String> = emptySet(), val externalParts: Map<Int, String> = emptyMap(), val archivePlan: ArchivePlan? = null,
    val rollingAudio: RollingAudio? = null, val representation: String = AudioRepresentation.ORIGINAL,
    val sourceOverride: OriginalAudioSource? = null, val microphone: MicrophoneOptions? = null) {
    val retention get() = RetentionMark(reference, pinned, holdForLaunch)
    /** The user already declined archival, but not recovery of unfinished work. */
    val completedTransientImport get() = importRetention == ImportRetentionChoice.DISCARD && temporary &&
        !pinned && !needsRecovery && processingAttempts.isEmpty() && status == "saved"
    val cursor get() = HistoryCursor(started, id)
    val durationMs: Long get() = sourceOverride?.durationMs ?: rollingAudio?.durationMs ?: if (sourceName == null) samples * 1000 / 16000 else importedDurationMs
    val seconds: Long get() = durationMs / 1000
    val parts: Int get() = sourceOverride?.parts ?: rollingAudio?.parts ?: if (sourceName != null) 1 else ((samples + RecordingHistory.PART_SAMPLES - 1) / RecordingHistory.PART_SAMPLES).toInt()
    fun part(index: Int) = sourceOverride?.asset(index)?.local ?: rollingAudio?.asset(directory, index)?.local ?: File(directory, sourceName ?: "audio-$index.wav")
    fun asset(index: Int) = sourceOverride?.asset(index) ?: rollingAudio?.asset(directory, index, externalParts[index]) ?: AudioPart(part(index), externalParts[index], mimeType,
        if (sourceName == null) minOf(RecordingHistory.PART_SAMPLES, samples - index * RecordingHistory.PART_SAMPLES) else null)
}

/** Private recording index and leases. All callers perform filesystem work on IO. */
class RecordingHistory(private val root: File, private val artifacts: ArchivalJobStore? = null,
    private val audioIO: AudioAssetIO = PrivateAudioAssetIO,
    private val installOriginal: (HistoryEntry) -> OriginalAudioSource = OriginalAudioSource::capture,
    private val clock: () -> Long = System::currentTimeMillis) {
    fun planEncoding(id: String, epoch: Long) {
        val entry = synchronized(this) { get(id).takeUnless { id in deferred || it.temporary } } ?: return
        val layout = entry.rollingAudio ?: return
        if (!layout.quality.encoding || layout.complete) return
        val journal = artifacts ?: return
        journal.enqueue(encodingRequest(entry), epoch)
    }
    private fun encodingRequest(entry: HistoryEntry): ArchivalRequest {
        val layout = checkNotNull(entry.rollingAudio)
        val config = File(entry.directory, "rolling.properties")
        val rep = encodingRepresentation(config)
        return ArchivalRequest(ArtifactOwner(ArtifactKind.AUDIO, entry.id), ArchivalOperation.ENCODE_AUDIO,
            config, entry.directory.toURI().toString(), "audio.${layout.quality.extension}",
            layout.quality.containerMime, created = entry.started, representation = rep, sourceRepresentation = entry.representation)
    }
    fun planImportEncoding(id: String, quality: AudioQuality, epoch: Long) {
        val lease = synchronized(this) { entries[id]?.takeUnless { id in deferred }?.let { acquire(id) } } ?: return
        try { synchronized(encodingPlans[(id.hashCode() and Int.MAX_VALUE) % encodingPlans.size]) { planImportEncodingOwned(id, quality, epoch) } }
        finally { lease.close() }
    }
    private fun planImportEncodingOwned(id: String, quality: AudioQuality, epoch: Long) {
        val entry = synchronized(this) { entries[id]?.takeUnless { id in deferred } } ?: return
        if (entry.rollingAudio != null || entry.temporary || entry.status in setOf("active", "discarded", "importing")) return
        val uncompressedCapture = entry.sourceName == null
        if (!quality.shouldEncodeImport(if (uncompressedCapture) false else entry.importMetadata?.isCompressed, true)) return
        val journal = artifacts ?: return
        if (journal.currentEpoch() != epoch) throw ArchivalCancelledException()
        val original = installOriginal(entry)
        synchronized(this) {
            val current = entries[id]?.takeUnless { id in deferred || it.status == "discarded" } ?: return
            if (current.representation != entry.representation || original.representation != entry.representation)
                throw ArchivalCancelledException()
            // COPY_AUDIO may have committed while installation ran on IO. Use
            // its current binding projection before the planning lease releases.
            reconcileOriginalBindings(current, original)
        }
        val spool = EncodingInput.prepareConversion(original, quality)
        val config = spool.config
        journal.enqueue(ArchivalRequest(ArtifactOwner(ArtifactKind.AUDIO, id), ArchivalOperation.ENCODE_AUDIO,
            config, entry.directory.toURI().toString(), "audio.${spool.quality.extension}", spool.quality.containerMime, created = entry.started,
            representation = encodingRepresentation(config), sourceRepresentation = entry.representation), epoch)
    }
    private fun encodingRepresentation(config: File): String {
        val p = HistoryMetadata.read(config)
        p.getProperty("representation")?.let { return it }
        val id = UUID.randomUUID().toString()
        p.setProperty("representation", id); AudioMetadata.write(config, p)
        return id
    }
    fun stopEncoding(id: String, reason: String) {
        val entry = synchronized(this) { entries[id] } ?: return
        if (File(entry.directory, "rolling.properties").isFile && entry.rollingAudio?.complete != true)
            (synchronized(this) { active[id]?.rolling } ?: RollingPcmJournal.open(entry.directory)).preservePcm(reason)
    }
    fun stopActiveEncoders(reason: String) {
        val ids = synchronized(this) { active.keys.toList() }
        ids.forEach { stopEncoding(it, reason) }
    }
    /** Called only after ordinary encoding failure and worker quiescence. Live
     * capture keeps writing PCM; its normal finish will enqueue the saved plan.
     * Already-finished capture must publish its fallback descriptors right now. */
    @Synchronized internal fun scheduleEncodingFallback(job: ArchivalJob): Boolean {
        val journal = artifacts ?: return true
        if (journal.currentEpoch() != job.epoch || journal.isDeleted(job.request.owner)) return true
        val entry = entries[job.request.owner.id]?.takeUnless { it.id in deferred || it.status == "discarded" } ?: return true
        if (entry.representation != job.request.sourceRepresentation || entry.archivePlan?.epoch?.let { it != job.epoch } == true) return true
        if (entry.status == "active") return false
        val updated = if (entry.rollingAudio?.liveCapture == true && !entry.rollingAudio.complete) {
            // finish() persisted the writer-owned end even if capture.properties
            // still contains an older periodic watermark. Preserve that complete
            // layout and commit its closure before any fallback can be published.
            commitFallbackClosure(entry)
            entry.copy(mimeType = if (entry.rollingAudio.encodedName == null) "audio/wav" else "audio/*")
        } else entry // imports retain their original, not their derivative spool
        save(updated); put(updated); queueArchive(updated, propagateFailure = true)
        return true
    }
    @Synchronized fun planArchive(id: String, plan: ArchivePlan) {
        val entry = entries[id]?.takeUnless { id in deferred || it.status == "discarded" } ?: return
        if (entry.temporary) return // A later explicit Keep gets its own settings snapshot.
        val updated = entry.copy(archivePlan = plan.copy(folder = entry.archivePlan?.folder ?: plan.folder))
        save(updated); put(updated); queueArchive(updated)
    }
    private fun queueArchive(entry: HistoryEntry, propagateFailure: Boolean = false) {
        val journal = artifacts ?: return
        if (entry.temporary || entry.status in setOf("active", "importing", "discarded")) return
        try {
            if (File(entry.directory, "rolling.properties").isFile && entry.rollingAudio?.complete != true &&
                RollingPcmJournal.open(entry.directory).state.encodingAllowed) return
            commitFallbackClosure(entry)
            entry.archivePlan?.let { plan ->
                for (part in 0 until entry.parts) if (part !in entry.externalParts) {
                    val file = entry.part(part)
                    journal.enqueue(ArchivalRequest(ArtifactOwner(ArtifactKind.AUDIO, entry.id), ArchivalOperation.COPY_AUDIO,
                        file, plan.folder, ManagedMarkdown.filename(entry.started, "Audio").removeSuffix(".md") + "-$part.${entry.asset(part).archiveExtension}",
                        entry.asset(part).mimeType, part, entry.started, representation = entry.representation,
                        sourceRepresentation = entry.representation), plan.epoch)
                }
            }
            if (entry.rollingAudio?.let { it.liveCapture && it.quality.encoding && !it.complete } == true)
                journal.completeFallback(encodingRequest(entry))
        } catch (_: ArchivalCancelledException) { /* Stop-all also cancels future producer output. */ }
        catch (e: Exception) { if (propagateFailure) throw e else journal.reportFailure(e) }
    }
    private fun commitFallbackClosure(entry: HistoryEntry) {
        val layout = entry.rollingAudio ?: return
        if (layout.liveCapture && !layout.complete && entry.status != "active")
            RollingPcmJournal.open(entry.directory).commitFinalizedCapture(layout.frames)
    }
    /** Metadata-only publication shares deletion's lock. The journal owns durable
     * URI identity; history holds a projection, rebuilt on startup. */
    @Synchronized fun publishArchive(job: ArchivalJob): Boolean {
        val entry = entries[job.request.owner.id]?.takeUnless { it.id in deferred || it.status == "discarded" } ?: return false
        if (job.request.operation == ArchivalOperation.ENCODE_AUDIO) {
            if (job.request.sourceRepresentation != entry.representation) return false
            val spool = RollingPcmJournal.open(entry.directory)
            val layout = entry.rollingAudio ?: RollingAudio(spool.quality, spool.partFrames, liveCapture = false)
            return checkNotNull(artifacts).publish(job.id, job.generation) { committed ->
                val file = File(java.net.URI(checkNotNull(committed.uri)))
                val frames = RollingPcmJournal.open(entry.directory).state.frames
                val updated = entry.copy(rollingAudio = layout.copy(frames = frames, prefixFrames = frames,
                    encodedName = file.name, complete = true), mimeType = layout.quality.containerMime,
                    representation = job.request.representation, externalParts = emptyMap())
                save(updated); put(updated); releasePrivateParts(updated); queueArchive(updated)
            }
        }
        if (job.request.representation != entry.representation) return false
        commitFallbackClosure(entry)
        require(job.request.owner.kind == ArtifactKind.AUDIO && job.request.part in 0 until entry.parts)
        require(job.request.source == entry.part(job.request.part))
        return checkNotNull(artifacts).publish(job.id, job.generation) { committed ->
            val updated = entry.copy(externalParts = entry.externalParts + (job.request.part to checkNotNull(committed.uri)))
            OriginalAudioSource.open(entry.directory)?.takeIf { it.representation == entry.representation }?.bind(job.request.part, checkNotNull(committed.uri))
            put(updated)
            releasePrivateParts(updated)
            retireSuperseded(updated)
        }
    }
    @Synchronized fun hasLeases(id: String): Boolean = (leases[id] ?: 0) > 0
    @Synchronized fun selectForExport(id: String): AudioExportSelection {
        val entry = get(id)
        check(entry.status !in setOf("active", "importing", "discarded") && entry.parts > 0) { "Recording is not available for export" }
        val lease = acquire(id)
        try { return AudioExportSelection(this, entry, lease) }
        catch (error: Exception) { lease.close(); throw error }
    }
    fun available(entry: HistoryEntry): Boolean = (0 until entry.parts).all {
        audioIO.available(entry.asset(it)) || (it == 0 && entry.rollingAudio?.let { layout ->
            !layout.complete && layout.prefixFrames > 0 && File(entry.directory, "audio.frames").isFile } == true)
    }
    fun prepareAudio(entry: HistoryEntry) { entry.rollingAudio?.let { RollingRecovery.ensurePrefix(entry.directory, it) } }
    /** A completed codec is not acknowledgement of a failed original consumer.
     * A leased retry prefers retained original tail PCM; its already reclaimed
     * prefix is remuxed from the same authoritative continuous codec stream. */
    fun processingAudio(entry: HistoryEntry): HistoryEntry {
        if (synchronized(this) { encodingCleanupOwner == entry.id } || File(entry.directory, "pcm-retired.properties").isFile) return entry
        OriginalAudioSource.open(entry.directory)?.let { original ->
            return entry.copy(rollingAudio = null, sourceName = original.sourceName, samples = original.samples,
                mimeType = original.mimeType, importedDurationMs = original.durationMs, externalParts = emptyMap(),
                representation = original.representation, sourceOverride = original)
        }
        if (entry.rollingAudio?.complete != true || entry.status !in setOf("failed", "interrupted") && entry.processingAttempts.isEmpty()) return entry
        val spool = RollingPcmJournal.open(entry.directory)
        val mixed = RollingRecovery.layout(spool)
        if (mixed.frames <= mixed.prefixFrames || !spool.pcm(mixed.prefixFrames / mixed.partFrames).isFile) return entry
        return entry.copy(rollingAudio = mixed, externalParts = emptyMap())
    }
    fun preparePart(entry: HistoryEntry, part: Int) {
        prepareAudio(entry)
        if (entry.rollingAudio != null && entry.status != "active") {
            val asset = entry.asset(part)
            if (asset.mimeType == "audio/wav" && asset.local.isFile) WavFile.repair(asset.local, asset.sampleRate, asset.channels)
        }
    }
    fun openPart(entry: HistoryEntry, part: Int): InputStream { preparePart(entry, part); return audioIO.open(entry.asset(part)) }
    companion object {
        const val PART_SAMPLES = 3600L * 16000
        private val encodingPlans = Array(32) { Any() }
    }
    private val entries = mutableMapOf<String, HistoryEntry>()
    private val ordered = HistoryIndex<HistoryEntry>()
    private val active = mutableMapOf<String, Recording>()
    private val leases = mutableMapOf<String, Int>()
    private val viewers = mutableMapOf<String, Int>()
    // Runtime membership only distinguishes live attempts from abandoned ones.
    // The authoritative recovery obligation is persisted in HistoryEntry.
    private val processingOwners = mutableMapOf<String, MutableSet<String>>()
    private val deferred = mutableSetOf<String>()
    private val privateCleanup = mutableSetOf<String>()
    private var encodingCleanupOwner: String? = null
    private val recoveries = mutableSetOf<String>()
    private val expiry = RetentionIndex()
    private var initialized = false
    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = changes

    @Synchronized fun initialize() {
        if (initialized) return
        check(root.mkdirs() || root.isDirectory) { "Cannot open recording history" }
        root.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val metadata = File(directory, "recording.properties")
            if (!metadata.exists()) return@forEach
            val properties = HistoryMetadata.read(metadata)
            var samples = properties.getProperty("samples", "0").toLong()
            var status = properties.getProperty("status", "interrupted")
            var rollingLayout = RollingAudio.read(properties)
            // Intent survives a crash between dismissal and physical deletion.
            // An incomplete encoded-file import has no published usable source.
            if (status == "discarded" || status == "importing" || artifacts?.isDeleted(ArtifactOwner(ArtifactKind.AUDIO, directory.name)) == true) {
                artifacts?.deleteOwner(ArtifactOwner(ArtifactKind.AUDIO, directory.name))
                directory.deleteRecursively()
                return@forEach
            }
            var reference = properties.getProperty("reference", "0").toLong()
            if (rollingLayout != null && (status == "active" || rollingLayout.liveCapture && !rollingLayout.complete &&
                    !RollingPcmJournal.open(directory).captureClosedOnDisk)) {
                if (!rollingLayout.complete) {
                    val spool = RollingPcmJournal.open(directory)
                    spool.recoverCapture()
                    rollingLayout = spool.captureLayout()
                }
                samples = rollingLayout.format.recognitionSamples(rollingLayout.frames)
                status = "interrupted"
            } else if (status == "active") {
                // Save write timestamps before header repair changes file metadata.
                val files = directory.listFiles()?.filter { it.extension == "wav" } ?: emptyList()
                reference = files.maxOfOrNull { it.lastModified() } ?: reference
                samples = 0
                for (file in files) {
                    if (file.length() >= 44) { WavFile.repair(file); samples += (file.length() - 44) / 2 }
                }
                status = "interrupted"
            }
            val mark = HistoryMetadata.readMark(properties, reference)
            val source = properties.getProperty("source")?.takeIf { it.isNotEmpty() }
            require(source == null || (File(source).name == source && source != "." && source != ".."))
            val importRetention = properties.getProperty("importRetention")?.let { value ->
                ImportRetentionChoice.entries.find { it.name == value }
            }
            val attempts = properties.getProperty("processingAttempts").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
            val recovery = properties.getProperty("recovery", "false").toBoolean() || attempts.isNotEmpty()
            val pendingImport = importRetention != null && recovery
            if ((pendingImport || attempts.isNotEmpty()) && status in setOf("ready", "saved")) status = "interrupted"
            val external = mutableMapOf<Int, String>()
            val owner = ArtifactOwner(ArtifactKind.AUDIO, directory.name)
            val representation = artifacts?.activeRepresentation(owner) ?: properties.getProperty("representation", AudioRepresentation.ORIGINAL)
            val original = OriginalAudioSource.open(directory) ?: OriginalAudioSource.recoverLegacy(directory, properties)
            artifacts?.forEachPublished(ArtifactOwner(ArtifactKind.AUDIO, directory.name)) {
                if (it.operation == ArchivalOperation.COPY_AUDIO && original?.representation == it.representation) original.bind(it.part, it.uri)
                if (it.representation != representation) return@forEachPublished
                if (it.operation == ArchivalOperation.ENCODE_AUDIO) {
                    val spool = RollingPcmJournal.open(directory)
                    rollingLayout = (rollingLayout ?: RollingAudio(spool.quality, spool.partFrames, liveCapture = false)).let { layout ->
                        val frames = spool.state.frames
                        layout.copy(frames = frames, prefixFrames = frames, complete = true, encodedName = File(java.net.URI(it.uri)).name)
                    }
                } else external[it.part] = it.uri
            }
            val entry = HistoryEntry(directory.name, directory, properties.getProperty("started", "0").toLong(), reference, samples, status,
                recovery, properties.getProperty("temporary", "false").toBoolean(),
                mark.pinned, mark.holdForLaunch, source, rollingLayout?.takeIf { it.complete }?.quality?.containerMime ?: properties.getProperty("mime", "audio/wav"),
                properties.getProperty("durationMs", "0").toLong(), properties.getProperty("failureMessage"), properties.getProperty("failureKind"),
                properties.getProperty("speakerLabels", "false").toBoolean(),
                // Old pending-recovery flags also covered ordinary temporary
                // results. Only actual failure/interruption establishes origin.
                properties.getProperty("recovered", "false").toBoolean() || status in setOf("failed", "interrupted") ||
                    properties.getProperty("failureKind") != null || pendingImport || attempts.isNotEmpty(),
                importRetention, if (properties.getProperty("importMetadata") == "true") AudioImportMetadata(
                    properties.getProperty("importName"), properties.getProperty("importMime"),
                    properties.getProperty("importDurationMs")?.toLongOrNull(), properties.getProperty("importBytes")?.toLongOrNull(),
                    properties.getProperty("importTrackMime"), properties.getProperty("importCompressed")?.toBooleanStrictOrNull()) else null,
                attempts, external, ArchivePlan.read(properties), rollingLayout, representation,
                microphone = if (properties.containsKey("microphone.gain")) MicrophoneOptions.fromMap(
                    MicrophoneOptions.HFP.toMap().keys.mapNotNull { name -> properties.getProperty("microphone.$name")?.let { name to it } }.toMap(),
                    MicrophoneOptions.STANDARD) else null)
            // A successful transcript-only import has no recovery obligation. All
            // runtime owners disappeared with the old process, so finish cleanup.
            if (entry.completedTransientImport) {
                if (!directory.deleteRecursively()) { put(entry); delete(entry.id) }
                return@forEach
            }
            put(entry)
            releasePrivateParts(entry)
            queueArchive(entry)
            retireSuperseded(entry)
            if (status == "interrupted") save(entry)
        }
        initialized = true
    }

    @Synchronized fun begin(retention: HistoryRetention, automatic: Boolean = retention != HistoryRetention.NONE,
        keepUntilDismissed: Boolean = false, quality: AudioQuality = AudioQuality(), microphone: MicrophoneOptions? = null): Recording {
        initialize()
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        check(directory.mkdir()) { "Cannot create recording history" }
        // Recovery is durable BEFORE the first PCM write. A process death must not
        // make the next startup's history-off cleanup erase an unfinished session.
        val rolling = if (quality.compress) RollingPcmJournal.create(directory, quality) else null
        val entry = HistoryEntry(id, directory, clock(), clock(), 0, "active", needsRecovery = true,
            temporary = !automatic || (keepUntilDismissed && retention == HistoryRetention.NONE),
            rollingAudio = rolling?.let { RollingAudio(quality, it.partFrames) }, microphone = microphone)
        save(entry)
        put(entry)
        return Recording(entry, retention, keepUntilDismissed, rolling).also { active[id] = it; leases[id] = 1 }
    }

    /** Copy the encoded source once; the caller's original is never owned or deleted. */
    fun importAudio(input: InputStream, extension: String, mime: String, durationMs: Long = 0): HistoryEntry =
        copyImport(input, extension, mime, durationMs, null, null, protectFromPruning = false) { entry, _ -> entry }

    data class OwnedImport(val entry: HistoryEntry, val lease: AudioLease)

    /** The protected lease is acquired in the same transaction that publishes the
     * complete copy. The caller must publish/close it on IO before a cancellable
     * dispatch; there is no prune window between return and viewer acquisition. */
    fun importAudioOwned(input: InputStream, extension: String, metadata: AudioImportMetadata,
        retention: ImportRetentionChoice): OwnedImport = copyImport(input, extension,
        metadata.mimeType ?: metadata.trackMimeType ?: "audio/*", metadata.durationMs ?: 0, retention, metadata,
        protectFromPruning = true) { entry, lease ->
        OwnedImport(entry, checkNotNull(lease))
    }

    private fun <T> copyImport(input: InputStream, extension: String, mime: String, durationMs: Long,
        retention: ImportRetentionChoice?, metadata: AudioImportMetadata?, protectFromPruning: Boolean,
        publish: (HistoryEntry, AudioLease?) -> T): T {
        val entry = synchronized(this) {
            initialize()
            val id = UUID.randomUUID().toString()
            val directory = File(root, id)
            check(directory.mkdir())
            val suffix = extension.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "audio" }
            HistoryEntry(id, directory, clock(), clock(), 0, "importing", needsRecovery = true,
                temporary = retention != ImportRetentionChoice.KEEP,
                sourceName = "source.$suffix", mimeType = mime, importedDurationMs = durationMs,
                importRetention = retention, importMetadata = metadata).also(::save)
        }
        try {
            entry.part(0).outputStream().use { input.copyTo(it, 64 * 1024) }
            // An empty provider stream is not owned replacement input. Reject it
            // before publishing readiness or starting expensive model preparation.
            check(entry.part(0).length() > 0) { "Audio file is empty" }
            val completed = entry.copy(status = "ready")
            return synchronized(this) {
                save(completed)
                // Protect before revision publication, including a reentrant
                // observer. Recovery flags do not bypass normal history expiry.
                val lease = if (protectFromPruning) {
                    viewers[completed.id] = 1
                    retain(completed.id, protectFromPruning = true)
                } else null
                put(completed)
                publish(completed, lease)
            }
        } catch (e: Exception) { entry.directory.deleteRecursively(); throw e }
    }

    /** Register durably before model preparation. A retry takes responsibility for
     * abandoned/failed attempts by replacing them atomically with its own token;
     * live siblings retain separate obligations. This also bounds failed retries'
     * metadata without ever leaving a crash window with no recovery obligation. */
    @Synchronized fun beginProcessing(id: String): ProcessingAttempt {
        val entry = get(id)
        check(id !in deferred) { "Recording was deleted" }
        val token = UUID.randomUUID().toString()
        val pending = entry.copy(needsRecovery = true,
            processingAttempts = entry.processingAttempts.intersect(processingOwners[id].orEmpty()) + token)
        save(pending)
        processingOwners.getOrPut(id) { mutableSetOf() }.add(token)
        put(pending)
        return ProcessingAttempt(id, token)
    }

    /** A handle acknowledges only its own persisted token. close without complete
     * is interruption, not success; it leaves recovery available even after all
     * runtime audio leases disappear. Terminal callbacks are idempotent. */
    inner class ProcessingAttempt internal constructor(val audioId: String, val token: String) : Closeable {
        private var ended = false
        fun complete() = finish(success = true)
        fun fail(message: String, kind: String) = finish(success = false, message = message, kind = kind)
        override fun close() = finish(success = false)

        private fun finish(success: Boolean, message: String? = null, kind: String? = null) = synchronized(this@RecordingHistory) {
            if (ended) return@synchronized
            try {
                val entry = entries[audioId]?.takeUnless {
                    audioId in deferred || it.status == "discarded" || token !in it.processingAttempts
                } ?: return@synchronized
                val updated = if (success) completedProcessing(entry, entry.processingAttempts - token)
                    else entry.copy(needsRecovery = true, recovered = true,
                        status = if (kind == null) "interrupted" else "failed",
                        failureMessage = message ?: entry.failureMessage, failureKind = kind ?: entry.failureKind)
                save(updated); put(updated)
                if (updated.completedTransientImport && (leases[audioId] ?: 0) == 0) delete(audioId)
            } finally {
                // Even failed metadata IO retires the runtime owner. The already
                // durable token remains pending and can be claimed by a retry.
                ended = true
                processingOwners[audioId]?.let { owners ->
                    owners.remove(token)
                    if (owners.isEmpty()) processingOwners.remove(audioId)
                }
            }
        }
    }

    @Synchronized fun list(page: Int = 0, pageSize: Int = 30): List<HistoryEntry> {
        initialize()
        return ordered.list(page, pageSize)
    }

    @Synchronized fun page(anchor: HistoryCursor? = null, direction: HistoryDirection = HistoryDirection.REFRESH,
        limit: Int = 30): HistoryPage<HistoryEntry> {
        initialize()
        return ordered.page(anchor, direction, limit)
    }

    @Synchronized fun recoveryCount(): Int = recoveries.size

    @Synchronized fun get(id: String): HistoryEntry = checkNotNull(entries[id]) { "Recording is unavailable" }

    /** Authorize a local-file ownership transfer against the current index, not
     * an earlier UI entry or lease-delayed bytes. Run on IO; the callback must
     * publish ownership synchronously and never wait for a Main-thread action. */
    @Synchronized internal fun withCompletedImport(id: String?, promote: () -> Boolean): Boolean {
        val entry = entries[id]?.takeUnless { it.id in deferred } ?: return false
        if (entry.sourceName == null || entry.status in setOf("importing", "active", "discarded") ||
            !entry.part(0).isFile || entry.part(0).length() == 0L) return false
        return promote()
    }

    @Synchronized fun acquire(id: String, protectFromPruning: Boolean = false): AudioLease {
        check(id in entries && id !in deferred) { "Recording expired or was deleted" }
        if (protectFromPruning) { viewers[id] = (viewers[id] ?: 0) + 1; expiry.remove(id) }
        return retain(id, protectFromPruning)
    }

    private fun retain(id: String, protectFromPruning: Boolean = false): AudioLease {
        leases[id] = (leases[id] ?: 0) + 1
        return AudioLease(id, protectFromPruning)
    }

    inner class AudioLease internal constructor(private val id: String, private val protectFromPruning: Boolean = false) : Closeable {
        private var closed = false
        private var selectedOriginal: OriginalAudioSource? = null
        val originalSource: OriginalAudioSource? get() = synchronized(this@RecordingHistory) {
            check(!closed)
            selectedOriginal ?: entries[id]?.takeUnless { encodingCleanupOwner == id || File(it.directory, "pcm-retired.properties").isFile }
                ?.let { OriginalAudioSource.open(it.directory) }?.also { selectedOriginal = it }
        }
        fun originalPcmReader(offset: Long = 0): OriginalAudioSource.PcmReader = synchronized(this@RecordingHistory) {
            val source = checkNotNull(originalSource)
            require(source.nativePcm && offset in 0..source.samples)
            source.pcmReader(audioIO, offset, retain(id))
        }
        /** Captured under the acquisition lock. Once cleanup reserves an owner,
         * new readers use its published representation even before old files unlink. */
        val originalImportFile: File? = if (encodingCleanupOwner == id) null else entries[id]?.let { entry ->
            if (File(entry.directory, "pcm-retired.properties").isFile) null
            else entry.sourceName?.let { File(entry.directory, it).takeIf(File::isFile) }
        }
        /** A reader derived from an existing lease remains valid if pruning became
         * due during model preparation. New unrelated readers are still rejected. */
        fun reader(offset: Long = 0): Reader = synchronized(this@RecordingHistory) {
            check(!closed) { "Audio lease is closed" }
            require(offset >= 0 && get(id).sourceName == null)
            check(get(id).rollingAudio == null || id in active) { "Retained rolling audio requires the part-format decoder" }
            Reader(get(id), retain(id), offset)
        }
        override fun close() = synchronized(this@RecordingHistory) {
            if (!closed) {
                closed = true
                if (protectFromPruning) {
                    val count = viewers.getValue(id) - 1
                    if (count == 0) viewers.remove(id) else viewers[id] = count
                    entries[id]?.let(::indexExpiry)
                }
                release(id)
            }
        }
    }

    @Synchronized fun prune(retention: HistoryRetention) {
        initialize()
        deferred.toList().filter { (leases[it] ?: 0) == 0 }.forEach(::remove)
        while (true) delete(expiry.firstDue(retention, clock()) ?: break)
        // Only committed parts are touched. A transient unlink failure is retried
        // on appropriate activity/retention maintenance, even for Forever entries.
        privateCleanup.toList().mapNotNull(entries::get).forEach(::releasePrivateParts)
    }

    @Synchronized fun setPinned(id: String, pinned: Boolean, duration: HistoryRetention, launch: String) {
        check(id !in deferred && id !in active) { "Recording is unavailable" }
        val entry = get(id)
        val mark = entry.retention.pin(pinned, duration, clock(), launch)
        val updated = entry.copy(temporary = false, pinned = mark.pinned, reference = mark.since, holdForLaunch = mark.holdForLaunch)
        save(updated); put(updated)
    }

    /** Only an actual user entry point calls this; background startup never releases holds. */
    @Synchronized fun onUserLaunch(launch: String) {
        initialize()
        expiry.releasedBy(launch).forEach { id ->
            val updated = get(id).copy(holdForLaunch = null)
            save(updated); put(updated)
        }
    }

    @Synchronized fun dismiss(id: String) {
        val entry = entries[id] ?: return
        if (entry.temporary) delete(id)
        else { val updated = entry.copy(needsRecovery = entry.processingAttempts.isNotEmpty()); save(updated); put(updated) }
    }

    /** Ordinary navigation hands temporary ownership to history only if policy
     * allows it. Keep the original reference time, never extend it on viewing. */
    @Synchronized fun retainOnExit(id: String, policy: HistoryExitPolicy, discardIfNeeded: Boolean = false): Boolean {
        val entry = entries[id]?.takeUnless { id in deferred } ?: return true
        // Consent was supplied at import. The last lease release owns cleanup;
        // navigating away from one view must not invalidate another active owner.
        if (entry.completedTransientImport) return true
        if (!policy.keeps(entry, now = clock())) {
            if (!discardIfNeeded) return false
            delete(id)
            return true
        }
        val updated = entry.copy(temporary = false, needsRecovery = entry.processingAttempts.isNotEmpty())
        save(updated); put(updated)
        return true
    }

    @Synchronized fun recordFailure(id: String, message: String, kind: String) {
        val entry = entries[id]?.takeUnless { id in deferred } ?: return
        val updated = entry.copy(needsRecovery = true, failureMessage = message, failureKind = kind, recovered = true)
        save(updated); put(updated)
    }

    @Synchronized fun markRecovered(id: String) {
        val entry = entries[id]?.takeUnless { id in deferred || it.recovered } ?: return
        val updated = entry.copy(recovered = true)
        save(updated); put(updated)
    }

    /** Record actual labeled output, including before capture finalization. Late
     * processing must never republish input the user has already discarded. */
    @Synchronized fun setSpeakerLabels(id: String, value: Boolean) {
        val entry = entries[id]?.takeUnless { id in deferred } ?: return
        if (entry.speakerLabels == value) return
        val updated = entry.copy(speakerLabels = value)
        save(updated); put(updated)
    }

    /** Legacy untracked completion. It cannot acknowledge any tracked attempt;
     * new producers must complete the handle returned by beginProcessing. */
    @Synchronized fun completeRecovery(id: String, retention: HistoryRetention) {
        val entry = get(id)
        if (!entry.needsRecovery || entry.processingAttempts.isNotEmpty() || id in active || id in deferred) return
        // Retrying does not extend saved audio's retention, and a temporary
        // dialog retains its source through successful retries until dismissal.
        val completed = completedProcessing(entry, emptySet())
        save(completed)
        put(completed)
        if (completed.completedTransientImport && (leases[id] ?: 0) == 0) delete(id)
    }

    private fun completedProcessing(entry: HistoryEntry, remaining: Set<String>): HistoryEntry = entry.copy(
        processingAttempts = remaining,
        status = if (remaining.isEmpty()) "saved" else entry.status,
        needsRecovery = remaining.isNotEmpty() || (entry.temporary && entry.importRetention == null),
        failureMessage = if (remaining.isEmpty()) null else entry.failureMessage,
        failureKind = if (remaining.isEmpty()) null else entry.failureKind)

    @Synchronized fun delete(id: String) {
        // Also handles a confirmed stable identity whose visible row vanished.
        artifacts?.deleteOwner(ArtifactOwner(ArtifactKind.AUDIO, id))
        if (id !in entries) return
        if (id !in deferred) {
            val discarded = get(id).copy(status = "discarded", needsRecovery = false, processingAttempts = emptySet())
            save(discarded)
            put(discarded)
        }
        deferred.add(id)
        processingOwners.remove(id)
        expiry.remove(id)
        if ((leases[id] ?: 0) == 0) remove(id)
    }

    fun read(id: String, offset: Long, count: Int): ShortArray {
        require(offset >= 0 && count in 0..3200)
        if (count == 0) return ShortArray(0)
        return openReader(id, offset).use { it.read(count) }
    }

    @Synchronized fun openReader(id: String, offset: Long = 0): Reader {
        require(offset >= 0)
        check(get(id).sourceName == null) { "Encoded audio requires AudioDecoder" }
        check(get(id).rollingAudio == null || id in active) { "Retained rolling audio requires the part-format decoder" }
        return Reader(get(id), acquire(id), offset)
    }

    /** IO is reader-owned, outside the history index lock. Only published bytes may be read. */
    inner class Reader internal constructor(private val entry: HistoryEntry, private val lease: Closeable,
        initialOffset: Long) : Closeable {
        var offset = initialOffset
            private set
        private var part = -1
        private var input: java.io.DataInputStream? = null
        private val bytes = ByteArray(6400)
        private var closed = false

        fun read(count: Int = 3200): ShortArray {
            check(!closed) { "Recording reader is closed" }
            require(count in 1..3200)
            val available = synchronized(this@RecordingHistory) { active[entry.id]?.writtenSamples ?: get(entry.id).samples }
            val result = ShortArray(minOf(count.toLong(), (available - offset).coerceAtLeast(0)).toInt())
            val rolling = entry.rollingAudio
            if (rolling != null && rolling.format.native) {
                val original = RollingPcmJournal.open(entry.directory).readFrames(offset, result.size)
                offset += original.size
                return original
            }
            val partSamples = rolling?.let { it.partFrames * 16000 / it.format.sampleRate } ?: PART_SAMPLES
            var copied = 0
            while (copied < result.size) {
                val index = (offset / partSamples).toInt()
                if (part != index) {
                    input?.close()
                    input = null
                    val raw = if (rolling != null) File(entry.directory, "audio-$index.wav").inputStream() else openPart(entry, index)
                    try {
                        // Sequential bounded reads also work with non-seekable SAF
                        // streams. Only the initial part needs an offset skip.
                        var skip = 44 + (offset % partSamples) * 2
                        while (skip > 0) {
                            val n = raw.skip(skip)
                            if (n > 0) skip -= n else { check(raw.read() >= 0) { "Incomplete audio part" }; skip-- }
                        }
                        input = java.io.DataInputStream(raw)
                    } catch (e: Exception) { raw.close(); throw e }
                    part = index
                }
                val partOffset = offset % partSamples
                val length = minOf((result.size - copied).toLong(), partSamples - partOffset).toInt()
                input!!.readFully(bytes, 0, length * 2)
                java.nio.ByteBuffer.wrap(bytes, 0, length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(result, copied, length)
                offset += length
                copied += length
            }
            return result
        }

        override fun close() {
            if (closed) return
            closed = true
            try { input?.close() } finally { lease.close() }
        }
    }

    private fun put(entry: HistoryEntry) {
        if (entry.externalParts.isNotEmpty()) privateCleanup.add(entry.id)
        if (entry.rollingAudio?.complete == true && File(entry.directory, "audio.frames").isFile) privateCleanup.add(entry.id)
        if (normalizedCleanupPending(entry)) privateCleanup.add(entry.id)
        entries.put(entry.id, entry)?.let { ordered.remove(it.cursor) }
        // Hide discarded entries immediately, even while leases delay physical
        // deletion. The visible index therefore needs no page-time filtering.
        if (entry.status !in setOf("active", "importing", "discarded") && entry.id !in deferred) {
            ordered.put(entry.cursor, entry)
        }
        if (entry.needsRecovery && entry.status !in setOf("active", "importing", "discarded")) recoveries.add(entry.id) else recoveries.remove(entry.id)
        indexExpiry(entry)
        changes.value++
    }
    private fun indexExpiry(entry: HistoryEntry) = expiry.put(entry.id, entry.retention,
        eligible = !entry.temporary && entry.status != "active" && entry.status != "discarded" &&
            entry.id !in deferred && entry.id !in viewers)
    private fun release(id: String) {
        val count = (leases[id] ?: 1) - 1
        if (count == 0) {
            leases.remove(id)
            if (id in deferred) remove(id)
            else if (entries[id]?.completedTransientImport == true) delete(id)
            else entries[id]?.let(::releasePrivateParts)
            artifacts?.readerReleased(ArtifactOwner(ArtifactKind.AUDIO, id))
        } else leases[id] = count
    }
    private fun releasePrivateParts(entry: HistoryEntry) {
        if ((leases[entry.id] ?: 0) != 0 || entry.id in active) return
        if (entry.rollingAudio?.complete == true && File(entry.directory, "audio.frames").isFile) { privateCleanup.add(entry.id); return }
        if (normalizedCleanupPending(entry)) { privateCleanup.add(entry.id); return }
        if (entry.externalParts.isNotEmpty()) commitFallbackClosure(entry)
        // Failed unlink is safe: startup and later lease releases retry. Only
        // committed, app-owned parts are eligible; metadata always stays private.
        // This is also the failure boundary if installation succeeded but the
        // planner was interrupted before its explicit reconciliation transaction.
        reconcileOriginalBindings(entry)
        var remaining = false
        entry.externalParts.keys.forEach { val file = entry.part(it); if (file.exists() && !file.delete()) remaining = true }
        if (!remaining) privateCleanup.remove(entry.id)
    }
    private fun reconcileOriginalBindings(entry: HistoryEntry, original: OriginalAudioSource? = OriginalAudioSource.open(entry.directory)) {
        check(Thread.holdsLock(this))
        if (original == null || original.representation != entry.representation) return
        require(original.parts == entry.parts) { "Original representation changed its part layout" }
        entry.externalParts.forEach(original::bind) // private metadata only; no provider IO
    }
    private fun normalizedCleanupPending(entry: HistoryEntry) = entry.rollingAudio?.let {
        it.liveCapture && !it.format.native && !File(entry.directory, "normalized-cleaned.properties").isFile
    } == true
    private fun originalCleanupAllowed(entry: HistoryEntry) = entry.rollingAudio?.complete == true &&
        entry.status !in setOf("failed", "interrupted") && entry.processingAttempts.isEmpty()
    private fun retireSuperseded(entry: HistoryEntry) {
        if (entry.representation == AudioRepresentation.ORIGINAL || !originalCleanupAllowed(entry)) return
        if (entry.archivePlan != null && entry.externalParts.size != entry.parts) return
        if (!File(entry.directory, "pcm-retired.properties").isFile) return
        artifacts?.retireSupersededRepresentations(ArtifactOwner(ArtifactKind.AUDIO, entry.id), entry.representation)
    }
    /** Bounded file cleanup on the archival drain, outside history locks and the
     * ASR/capture completion path. A runtime lease protects directory ownership;
     * newly acquired readers choose the already-published encoded asset. */
    fun cleanupPublishedEncoding(budget: Int = 32): Boolean {
        val selected = synchronized(this) {
            if (encodingCleanupOwner != null) return false
            val entry = privateCleanup.asSequence().mapNotNull(entries::get).firstOrNull {
                it.id !in active && it.status != "active" && it.id !in deferred && (leases[it.id] ?: 0) == 0 &&
                    (originalCleanupAllowed(it) || normalizedCleanupPending(it))
            } ?: return false
            encodingCleanupOwner = entry.id
            entry to retain(entry.id)
        }
        val (entry, lease) = selected
        val dropOriginal = originalCleanupAllowed(entry)
        var exhausted = true
        var complete = false
        try {
            val retirement = File(entry.directory, "pcm-retired.properties")
            if (dropOriginal && !retirement.isFile) AudioMetadata.write(retirement, Properties().apply { setProperty("retired", "true") })
            var removed = 0
            java.nio.file.Files.newDirectoryStream(entry.directory.toPath()).use { files ->
                for (path in files) {
                    val name = path.fileName.toString()
                    if (name == entry.rollingAudio!!.encodedName) continue
                    val normalized = Regex("audio-[0-9]+\\.wav").matches(name)
                    val original = name == entry.sourceName || name.startsWith("recovered-prefix.") || Regex("capture-[0-9]+\\.wav").matches(name)
                    if (normalized || dropOriginal && original) {
                        if (removed >= budget) { exhausted = false; break }
                        java.nio.file.Files.deleteIfExists(path); removed++
                    }
                }
            }
            if (exhausted) {
                if (dropOriginal) java.nio.file.Files.deleteIfExists(File(entry.directory, "audio.frames").toPath())
                if (dropOriginal) synchronized(this) { entries[entry.id]?.let(::retireSuperseded) }
                if (entry.rollingAudio?.liveCapture == true && !entry.rollingAudio.format.native)
                    AudioMetadata.write(File(entry.directory, "normalized-cleaned.properties"), Properties())
                complete = true
            }
        } finally {
            synchronized(this) {
                encodingCleanupOwner = null
                if (complete) privateCleanup.remove(entry.id)
            }
            lease.close()
        }
        return !exhausted
    }
    private fun remove(id: String) {
        val entry = entries[id] ?: return
        // Keep the index/deferred marker if deletion fails, so the next prune retries.
        if (!entry.directory.deleteRecursively()) return
        entries.remove(id); ordered.remove(entry.cursor); deferred.remove(id)
        privateCleanup.remove(id)
        recoveries.remove(id)
        expiry.remove(id)
        changes.value++
    }
    private fun save(entry: HistoryEntry) {
        val properties = Properties().apply {
            setProperty("started", entry.started.toString()); setProperty("reference", entry.reference.toString())
            setProperty("samples", entry.samples.toString()); setProperty("status", entry.status)
            setProperty("recovery", entry.needsRecovery.toString()); setProperty("temporary", entry.temporary.toString())
            if (entry.processingAttempts.isNotEmpty()) setProperty("processingAttempts", entry.processingAttempts.joinToString(","))
            HistoryMetadata.writeMark(this, entry.retention)
            entry.sourceName?.let { setProperty("source", it) }
            setProperty("mime", entry.mimeType); setProperty("durationMs", entry.importedDurationMs.toString())
            setProperty("speakerLabels", entry.speakerLabels.toString())
            setProperty("recovered", entry.recovered.toString())
            entry.archivePlan?.writeTo(this)
            entry.rollingAudio?.write(this)
            setProperty("representation", entry.representation)
            entry.failureMessage?.let { setProperty("failureMessage", it) }
            entry.failureKind?.let { setProperty("failureKind", it) }
            // Android source/effects are already reflected in these original
            // samples. Only software gain is replayed; old/imported audio has none.
            entry.microphone?.toMap()?.forEach { (name, value) -> setProperty("microphone.$name", value) }
            entry.importRetention?.let { setProperty("importRetention", it.name) }
            entry.importMetadata?.let { metadata ->
                setProperty("importMetadata", "true")
                metadata.displayName?.let { setProperty("importName", it) }
                metadata.mimeType?.let { setProperty("importMime", it) }
                metadata.durationMs?.let { setProperty("importDurationMs", it.toString()) }
                metadata.sourceBytes?.let { setProperty("importBytes", it.toString()) }
                metadata.trackMimeType?.let { setProperty("importTrackMime", it) }
                metadata.isCompressed?.let { setProperty("importCompressed", it.toString()) }
            }
        }
        if (entry.rollingAudio != null) AudioMetadata.write(File(entry.directory, "recording.properties"), properties)
        else HistoryMetadata.write(File(entry.directory, "recording.properties"), properties)
    }

    inner class Recording internal constructor(val entry: HistoryEntry, private val retention: HistoryRetention,
        private val keepUntilDismissed: Boolean = false, val rolling: RollingPcmJournal? = null) {
        @Volatile var writtenSamples = 0L
            private set
        private var wav: WavFile? = null
        private var part = -1
        private var finished = false
        private var captureClosed = false
        private val converter = rolling?.format?.takeUnless { it.native }?.let {
            io.github.lrq3000.utterlane.transcribe.StreamingResampler(it.sampleRate, it.channels)
        }
        private val partSamples get() = rolling?.let { it.partFrames * 16000 / it.format.sampleRate } ?: PART_SAMPLES

        fun append(samples: ShortArray) {
            check(!finished)
            rolling?.append(samples)
            if (rolling != null && converter == null) { writtenSamples += samples.size; return }
            appendNormalized(converter?.accept(FloatArray(samples.size) { samples[it] / 32768f }) ?: samples)
        }
        private fun appendNormalized(samples: ShortArray) {
            var offset = 0
            while (offset < samples.size) {
                val index = (writtenSamples / partSamples).toInt()
                if (index != part) { wav?.close(); wav = WavFile(File(entry.directory, "audio-$index.wav")); part = index }
                val length = minOf((samples.size - offset).toLong(), partSamples - writtenSamples % partSamples).toInt()
                wav!!.append(if (offset == 0 && length == samples.size) samples else samples.copyOfRange(offset, offset + length))
                // Publication follows the completed write; readers cannot observe unwritten bytes.
                writtenSamples += length
                offset += length
            }
        }
        fun closeCapture() {
            if (captureClosed) return
            captureClosed = true
            converter?.finish()?.let(::appendNormalized)
            rolling?.closeCapture()
        }

        fun finish(failed: Boolean) {
            if (finished) return
            finished = true
            var incomplete = failed
            try { closeCapture(); wav?.close() }
            catch (e: Exception) { incomplete = true; throw e }
            finally {
                synchronized(this@RecordingHistory) {
                    val discarded = entry.id in deferred
                    // Recognition can publish metadata while capture owns its
                    // original snapshot. Finalization only owns these fields.
                    val current = get(entry.id)
                    val keepTemporary = keepUntilDismissed && current.temporary
                    var completed = current.copy(reference = clock(), samples = writtenSamples, status = if (discarded) "discarded" else if (incomplete) "failed" else "saved",
                        needsRecovery = !discarded && (incomplete || keepTemporary) && writtenSamples > 0,
                        recovered = current.recovered || incomplete,
                        rollingAudio = current.rollingAudio?.let { if (it.complete) it else rolling!!.captureLayout() })
                    try { save(completed) }
                    catch (e: Exception) {
                        // The initial active/recovery metadata is still durable.
                        // Mirror that protection in memory if final metadata fails.
                        completed = completed.copy(status = if (discarded) "discarded" else "failed", needsRecovery = !discarded && writtenSamples > 0)
                        throw e
                    } finally { put(completed); active.remove(entry.id); release(entry.id) }
                    // Home explicitly owns successful temporary results until
                    // dismissal; other entry points keep immediate cleanup.
                    if (writtenSamples == 0L || (!incomplete && !keepTemporary && (current.temporary || retention == HistoryRetention.NONE))) delete(entry.id)
                    else queueArchive(completed)
                }
            }
        }
    }
}
