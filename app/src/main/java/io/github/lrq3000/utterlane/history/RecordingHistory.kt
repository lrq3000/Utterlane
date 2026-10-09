package io.github.lrq3000.utterlane.history

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.UUID

data class HistoryEntry(val id: String, val directory: File, val started: Long, val reference: Long, val samples: Long, val status: String,
    val needsRecovery: Boolean = false, val temporary: Boolean = false,
    val pinned: Boolean = false, val holdForLaunch: String? = null,
    val sourceName: String? = null, val mimeType: String = "audio/wav", val importedDurationMs: Long = 0,
    val failureMessage: String? = null, val failureKind: String? = null, val speakerLabels: Boolean = false) {
    val retention get() = RetentionMark(reference, pinned, holdForLaunch)
    val cursor get() = HistoryCursor(started, id)
    val durationMs: Long get() = if (sourceName == null) samples * 1000 / 16000 else importedDurationMs
    val seconds: Long get() = durationMs / 1000
    val parts: Int get() = if (sourceName != null) 1 else ((samples + RecordingHistory.PART_SAMPLES - 1) / RecordingHistory.PART_SAMPLES).toInt()
    fun part(index: Int) = File(directory, sourceName ?: "audio-$index.wav")
}

/** Private recording index and leases. All callers perform filesystem work on IO. */
class RecordingHistory(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object { const val PART_SAMPLES = 3600L * 16000 }
    private val entries = mutableMapOf<String, HistoryEntry>()
    private val ordered = HistoryIndex<HistoryEntry>()
    private val active = mutableMapOf<String, Recording>()
    private val leases = mutableMapOf<String, Int>()
    private val deferred = mutableSetOf<String>()
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
            // Intent survives a crash between dismissal and physical deletion.
            // An incomplete encoded-file import has no published usable source.
            if (status == "discarded" || status == "importing") {
                directory.deleteRecursively()
                return@forEach
            }
            var reference = properties.getProperty("reference", "0").toLong()
            if (status == "active") {
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
            val entry = HistoryEntry(directory.name, directory, properties.getProperty("started", "0").toLong(), reference, samples, status,
                properties.getProperty("recovery", "false").toBoolean(), properties.getProperty("temporary", "false").toBoolean(),
                mark.pinned, mark.holdForLaunch, source, properties.getProperty("mime", "audio/wav"),
                properties.getProperty("durationMs", "0").toLong(), properties.getProperty("failureMessage"), properties.getProperty("failureKind"),
                properties.getProperty("speakerLabels", "false").toBoolean())
            put(entry)
            if (status == "interrupted") save(entry)
        }
        initialized = true
    }

    @Synchronized fun begin(retention: HistoryRetention, automatic: Boolean = retention != HistoryRetention.NONE,
        keepUntilDismissed: Boolean = false): Recording {
        initialize()
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        check(directory.mkdir()) { "Cannot create recording history" }
        // Recovery is durable BEFORE the first PCM write. A process death must not
        // make the next startup's history-off cleanup erase an unfinished session.
        val entry = HistoryEntry(id, directory, clock(), clock(), 0, "active", needsRecovery = true,
            temporary = !automatic || (keepUntilDismissed && retention == HistoryRetention.NONE))
        save(entry)
        put(entry)
        return Recording(entry, retention, keepUntilDismissed).also { active[id] = it; leases[id] = 1 }
    }

    /** Copy the encoded source once; the caller's original is never owned or deleted. */
    fun importAudio(input: InputStream, extension: String, mime: String, durationMs: Long = 0): HistoryEntry {
        val entry = synchronized(this) {
            initialize()
            val id = UUID.randomUUID().toString()
            val directory = File(root, id)
            check(directory.mkdir())
            val suffix = extension.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "audio" }
            HistoryEntry(id, directory, clock(), clock(), 0, "importing", needsRecovery = true, temporary = true,
                sourceName = "source.$suffix", mimeType = mime, importedDurationMs = durationMs).also(::save)
        }
        try {
            entry.part(0).outputStream().use { input.copyTo(it, 64 * 1024) }
            // An empty provider stream is not owned replacement input. Reject it
            // before publishing readiness or starting expensive model preparation.
            check(entry.part(0).length() > 0) { "Audio file is empty" }
            val completed = entry.copy(status = "ready")
            synchronized(this) { save(completed); put(completed) }
            return completed
        } catch (e: Exception) { entry.directory.deleteRecursively(); throw e }
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

    @Synchronized fun acquire(id: String): AudioLease {
        check(id in entries && id !in deferred) { "Recording expired or was deleted" }
        return retain(id)
    }

    private fun retain(id: String): AudioLease {
        leases[id] = (leases[id] ?: 0) + 1
        return AudioLease(id)
    }

    inner class AudioLease internal constructor(private val id: String) : Closeable {
        private var closed = false
        /** A reader derived from an existing lease remains valid if pruning became
         * due during model preparation. New unrelated readers are still rejected. */
        fun reader(offset: Long = 0): Reader = synchronized(this@RecordingHistory) {
            check(!closed) { "Audio lease is closed" }
            require(offset >= 0 && get(id).sourceName == null)
            Reader(get(id), retain(id), offset)
        }
        override fun close() = synchronized(this@RecordingHistory) {
            if (!closed) { closed = true; release(id) }
        }
    }

    @Synchronized fun prune(retention: HistoryRetention) {
        initialize()
        deferred.toList().filter { (leases[it] ?: 0) == 0 }.forEach(::remove)
        while (true) delete(expiry.firstDue(retention, clock()) ?: break)
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
        else { val updated = entry.copy(needsRecovery = false); save(updated); put(updated) }
    }

    @Synchronized fun recordFailure(id: String, message: String, kind: String) {
        val entry = entries[id]?.takeUnless { id in deferred } ?: return
        val updated = entry.copy(needsRecovery = true, failureMessage = message, failureKind = kind)
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

    /** Call only after the entire recovered transcript (including speaker EOF) is saved. */
    @Synchronized fun completeRecovery(id: String, retention: HistoryRetention) {
        val entry = get(id)
        if (!entry.needsRecovery || id in active || id in deferred) return
        // Retrying does not extend saved audio's retention, and a temporary
        // dialog retains its source through successful retries until dismissal.
        val completed = entry.copy(status = "saved", needsRecovery = entry.temporary, failureMessage = null, failureKind = null)
        save(completed)
        put(completed)
    }

    @Synchronized fun delete(id: String) {
        if (id !in entries) return
        if (id !in deferred) {
            val discarded = get(id).copy(status = "discarded", needsRecovery = false)
            save(discarded)
            put(discarded)
        }
        deferred.add(id)
        expiry.remove(id)
        if ((leases[id] ?: 0) == 0) remove(id)
    }

    @Synchronized fun read(id: String, offset: Long, count: Int): ShortArray {
        require(offset >= 0 && count in 0..3200)
        val entry = checkNotNull(entries[id]) { "Recording is unavailable" }
        check(entry.sourceName == null) { "Encoded audio requires AudioDecoder" }
        val available = active[id]?.writtenSamples ?: entry.samples
        val result = ShortArray(minOf(count.toLong(), available - offset).coerceAtLeast(0).toInt())
        var copied = 0
        while (copied < result.size) {
            val position = offset + copied
            val part = (position / PART_SAMPLES).toInt()
            val partOffset = position % PART_SAMPLES
            val length = minOf((result.size - copied).toLong(), PART_SAMPLES - partOffset).toInt()
            WavFile.read(entry.part(part), partOffset, length).copyInto(result, copied)
            copied += length
        }
        return result
    }

    @Synchronized fun openReader(id: String, offset: Long = 0): Reader {
        require(offset >= 0)
        check(get(id).sourceName == null) { "Encoded audio requires AudioDecoder" }
        return Reader(get(id), acquire(id), offset)
    }

    /** IO is reader-owned, outside the history index lock. Only published bytes may be read. */
    inner class Reader internal constructor(private val entry: HistoryEntry, private val lease: Closeable,
        initialOffset: Long) : Closeable {
        var offset = initialOffset
            private set
        private var part = -1
        private var input: WavFile.Reader? = null
        private var closed = false

        fun read(count: Int = 3200): ShortArray {
            check(!closed) { "Recording reader is closed" }
            require(count in 1..3200)
            val available = synchronized(this@RecordingHistory) { active[entry.id]?.writtenSamples ?: get(entry.id).samples }
            val result = ShortArray(minOf(count.toLong(), (available - offset).coerceAtLeast(0)).toInt())
            var copied = 0
            while (copied < result.size) {
                val index = (offset / PART_SAMPLES).toInt()
                if (part != index) {
                    input?.close()
                    input = null
                    input = WavFile.Reader(entry.part(index))
                    part = index
                }
                val partOffset = offset % PART_SAMPLES
                val length = minOf((result.size - copied).toLong(), PART_SAMPLES - partOffset).toInt()
                input!!.readInto(partOffset, result, copied, length)
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
        entries.put(entry.id, entry)?.let { ordered.remove(it.cursor) }
        // Hide discarded entries immediately, even while leases delay physical
        // deletion. The visible index therefore needs no page-time filtering.
        if (entry.status !in setOf("active", "importing", "discarded") && entry.id !in deferred) {
            ordered.put(entry.cursor, entry)
        }
        if (entry.needsRecovery && entry.status !in setOf("active", "importing", "discarded")) recoveries.add(entry.id) else recoveries.remove(entry.id)
        expiry.put(entry.id, entry.retention, eligible = !entry.temporary && entry.status != "active" && entry.status != "discarded")
        changes.value++
    }
    private fun release(id: String) {
        val count = (leases[id] ?: 1) - 1
        if (count == 0) { leases.remove(id); if (id in deferred) remove(id) } else leases[id] = count
    }
    private fun remove(id: String) {
        val entry = entries[id] ?: return
        // Keep the index/deferred marker if deletion fails, so the next prune retries.
        if (!entry.directory.deleteRecursively()) return
        entries.remove(id); ordered.remove(entry.cursor); deferred.remove(id)
        recoveries.remove(id)
        expiry.remove(id)
        changes.value++
    }
    private fun save(entry: HistoryEntry) {
        val properties = Properties().apply {
            setProperty("started", entry.started.toString()); setProperty("reference", entry.reference.toString())
            setProperty("samples", entry.samples.toString()); setProperty("status", entry.status)
            setProperty("recovery", entry.needsRecovery.toString()); setProperty("temporary", entry.temporary.toString())
            HistoryMetadata.writeMark(this, entry.retention)
            entry.sourceName?.let { setProperty("source", it) }
            setProperty("mime", entry.mimeType); setProperty("durationMs", entry.importedDurationMs.toString())
            setProperty("speakerLabels", entry.speakerLabels.toString())
            entry.failureMessage?.let { setProperty("failureMessage", it) }
            entry.failureKind?.let { setProperty("failureKind", it) }
        }
        HistoryMetadata.write(File(entry.directory, "recording.properties"), properties)
    }

    inner class Recording internal constructor(val entry: HistoryEntry, private val retention: HistoryRetention,
        private val keepUntilDismissed: Boolean = false) {
        @Volatile var writtenSamples = 0L
            private set
        private var wav: WavFile? = null
        private var part = -1
        private var finished = false

        fun append(samples: ShortArray) {
            check(!finished)
            var offset = 0
            while (offset < samples.size) {
                val index = (writtenSamples / PART_SAMPLES).toInt()
                if (index != part) { wav?.close(); wav = WavFile(entry.part(index)); part = index }
                val length = minOf((samples.size - offset).toLong(), PART_SAMPLES - writtenSamples % PART_SAMPLES).toInt()
                wav!!.append(if (offset == 0 && length == samples.size) samples else samples.copyOfRange(offset, offset + length))
                // Publication follows the completed write; readers cannot observe unwritten bytes.
                writtenSamples += length
                offset += length
            }
        }

        fun finish(failed: Boolean) {
            if (finished) return
            finished = true
            var incomplete = failed
            try { wav?.close() }
            catch (e: Exception) { incomplete = true; throw e }
            finally {
                synchronized(this@RecordingHistory) {
                    val discarded = entry.id in deferred
                    // Recognition can publish metadata while capture owns its
                    // original snapshot. Finalization only owns these fields.
                    val current = get(entry.id)
                    val keepTemporary = keepUntilDismissed && current.temporary
                    var completed = current.copy(reference = clock(), samples = writtenSamples, status = if (discarded) "discarded" else if (incomplete) "failed" else "saved",
                        needsRecovery = !discarded && (incomplete || keepTemporary) && writtenSamples > 0)
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
                }
            }
        }
    }
}
