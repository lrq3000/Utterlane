package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.asr.TranscriptDiscardedException
import java.io.Closeable
import java.io.File
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TranscriptEntry(val id: String, val directory: File, val created: Long, val model: String,
    val audioId: String?, val retention: RetentionMark, val modelId: String? = null,
    val durationMs: Long = 0, val speakerLabels: Boolean = false, val recovered: Boolean = false) {
    val file get() = File(directory, "transcript.txt")
    val cursor get() = HistoryCursor(created, id)
}

/** Text history owns independent copies; deleting source audio cannot cascade into it. */
class TranscriptHistory(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        fun idForAttempt(attempt: String): String = UUID.nameUUIDFromBytes(attempt.toByteArray(Charsets.UTF_8)).toString()
    }
    private val entries = mutableMapOf<String, TranscriptEntry>()
    // Derived from each transcript's persisted audioId, never a second source of
    // truth. Lookup touches only the versions associated with one recording.
    private val byAudio = mutableMapOf<String, MutableSet<String>>()
    private val ordered = HistoryIndex<TranscriptEntry>()
    private val expiry = RetentionIndex()
    private val leases = mutableMapOf<String, Int>()
    private val viewers = mutableMapOf<String, Int>()
    private val deleted = mutableSetOf<String>()
    private var initialized = false
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes

    @Synchronized fun initialize() {
        if (initialized) return
        check(root.mkdirs() || root.isDirectory)
        root.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val metadata = File(directory, "transcript.properties")
            if (!metadata.isFile) { directory.deleteRecursively(); return@forEach }
            val p = HistoryMetadata.read(metadata)
            if (p.getProperty("discarded", "false").toBoolean()) { directory.deleteRecursively(); return@forEach }
            val created = p.getProperty("created").toLong()
            if (File(directory, "transcript.txt").isFile) put(TranscriptEntry(directory.name, directory, created,
                p.getProperty("model", ""), p.getProperty("audioId"), HistoryMetadata.readMark(p, created), p.getProperty("modelId"),
                p.getProperty("durationMs", "0").toLong(), p.getProperty("speakerLabels", "false").toBoolean(),
                p.getProperty("recovered", "false").toBoolean()))
        }
        initialized = true
    }

    /** A stable attempt ID deduplicates manual saving; a new recognition attempt uses a new ID. */
    @Synchronized fun save(source: File, model: String, audioId: String? = null, pinned: Boolean = false,
        attempt: String = source.name, modelId: String? = null, created: Long? = null,
        durationMs: Long = 0, speakerLabels: Boolean = false, recovered: Boolean = false,
        reference: Long? = null): TranscriptEntry {
        // Share the source-disposition lock with deletion. A producer either
        // publishes before the marker (and is then deleted by its confirmed ID),
        // or observes the marker and cannot recreate that result afterward.
        return TranscriptSource.withActiveSource(source) {
            initialize()
            val id = idForAttempt(attempt)
            entries[id]?.let { existing ->
                check(id !in deleted) { "Transcript was deleted" }
                if (recovered || TranscriptSource.read(source).recovered) markRecovered(id)
                if (pinned && !existing.retention.pinned) setPinned(id, true, HistoryRetention.FOREVER, "")
                return@withActiveSource get(id)
            }
            check(source.isFile && source.length() > 0) { "There is no transcript to save" }
            val directory = File(root, id)
            check(directory.mkdir()) { "Cannot create transcript history" }
            // Source chronology is independent of saving time. Ordinary recovery
            // handoff supplies its original age; an explicit new save starts now.
            val now = clock()
            val entry = TranscriptEntry(id, directory, created ?: now, model, audioId, RetentionMark(reference ?: now, pinned), modelId,
                durationMs, speakerLabels, recovered || TranscriptSource.read(source).recovered)
            try {
                source.inputStream().use { input -> entry.file.outputStream().use { input.copyTo(it, 64 * 1024) } }
                persist(entry)
                put(entry)
                entry
            } catch (e: Exception) { directory.deleteRecursively(); throw e }
        }
    }

    @Synchronized fun get(id: String): TranscriptEntry = checkNotNull(entries[id]?.takeIf { id !in deleted }) { "Transcript is unavailable" }

    /** Explicit Keep creates a new identity only when the previous saved copy is
     * absent. Its working provenance must publish that same identity. */
    @Synchronized fun saveWorking(store: TranscriptStore, model: String, audioId: String?, modelId: String?,
        metadata: TranscriptMetadata): TranscriptEntry {
        val saved = save(store.file, model, audioId, pinned = true, attempt = UUID.randomUUID().toString(),
            modelId = modelId, created = metadata.created, durationMs = metadata.durationMs, speakerLabels = metadata.speakerLabels,
            recovered = metadata.recovered)
        try {
            store.attachSource(TranscriptSource(audioId, saved.id, model, modelId, recovered = saved.recovered))
            return saved
        } catch (error: Exception) {
            // This ID belongs solely to this incomplete Keep. Roll it back on
            // sidecar IO failure or a concurrent ordinary working-copy dismissal;
            // never leave an unassociated pinned result, or touch a sibling ID.
            try { delete(saved.id) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    /** The history monitor is already the publication lock (history -> source).
     * Extend it over identity rechecks/provenance, not coroutine suspension or
     * waiting for producers. All dialogs share this repository; unrelated source
     * locks remain bounded and no second lock order is introduced. Call on IO. */
    @Synchronized fun <T> withPublicationLock(action: () -> T): T = action()

    /** Consume an unexposed owner: return that same live store after migration,
     * or release its leases without discarding recoverable bytes on failure. */
    fun migrateWorking(store: TranscriptStore, legacy: TranscriptSource): TranscriptStore {
        var transferred = false
        try {
            return withPublicationLock {
                // Construction can predate another owner's complete Keep. Read,
                // fill missing associations and write within the same transaction
                // as Keep/confirmation; the constructor snapshot is not authority.
                // Lock order remains history -> store -> source (no source lock
                // is held while waiting for the history or store monitor).
                val current = TranscriptSource.read(store.file)
                if (current.discarded) throw TranscriptDiscardedException()
                store.attachSource(current.copy(audioId = current.audioId ?: legacy.audioId,
                    transcriptId = current.transcriptId ?: legacy.transcriptId,
                    modelName = current.modelName.ifBlank { legacy.modelName },
                    modelId = current.modelId ?: legacy.modelId))
                transferred = true
                store
            }
        } finally {
            // The model has not exposed this owner yet. A sidecar IO failure is
            // not explicit dismissal; closing leases must not mark valid text.
            if (!transferred) store.keepForRecovery()
        }
    }

    @Synchronized fun find(id: String?): TranscriptEntry? {
        initialize()
        return entries[id]?.takeIf { it.id !in deleted }
    }

    @Synchronized fun markRecovered(id: String) {
        val entry = entries[id]?.takeUnless { id in deleted || it.recovered } ?: return
        val updated = entry.copy(recovered = true)
        persist(updated); put(updated)
    }

    @Synchronized fun forAudio(audioId: String): List<TranscriptEntry> {
        initialize()
        return byAudio[audioId]?.mapNotNull { entries[it] } ?: emptyList()
    }
    @Synchronized fun list(page: Int = 0, pageSize: Int = 30): List<TranscriptEntry> {
        initialize()
        return ordered.list(page, pageSize)
    }

    @Synchronized fun page(anchor: HistoryCursor? = null, direction: HistoryDirection = HistoryDirection.REFRESH,
        limit: Int = 30): HistoryPage<TranscriptEntry> {
        initialize()
        return ordered.page(anchor, direction, limit)
    }

    @Synchronized fun setPinned(id: String, value: Boolean, duration: HistoryRetention, launch: String) {
        checkNotNull(setPinnedIfPresent(id, value, duration, launch)) { "Transcript is unavailable" }
    }

    /** Absence and pinning are one index operation: callers can offer a new
     * explicit copy without mistaking a discarded-but-leased file for history. */
    @Synchronized fun setPinnedIfPresent(id: String, value: Boolean, duration: HistoryRetention, launch: String): TranscriptEntry? {
        val entry = entries[id]?.takeUnless { id in deleted } ?: return null
        val updated = entry.copy(retention = entry.retention.pin(value, duration, clock(), launch))
        persist(updated); put(updated)
        return updated
    }
    @Synchronized fun onUserLaunch(launch: String) {
        initialize()
        expiry.releasedBy(launch).forEach { id ->
            val entry = get(id)
            val updated = entry.copy(retention = entry.retention.copy(holdForLaunch = null))
            persist(updated); put(updated)
        }
    }
    @Synchronized fun prune(duration: HistoryRetention) {
        initialize()
        deleted.toList().filter { (leases[it] ?: 0) == 0 }.forEach(::remove)
        while (true) delete(expiry.firstDue(duration, clock()) ?: break)
    }
    @Synchronized fun delete(id: String) {
        val entry = entries[id] ?: return
        persist(entry, discarded = true)
        unlink(entry)
        deleted.add(id); ordered.remove(entry.cursor); expiry.remove(id); changes.value++
        if ((leases[id] ?: 0) == 0) remove(id)
    }
    @Synchronized fun acquire(id: String, protectFromPruning: Boolean = false): Closeable {
        get(id)
        leases[id] = (leases[id] ?: 0) + 1
        if (protectFromPruning) { viewers[id] = (viewers[id] ?: 0) + 1; expiry.remove(id) }
        var closed = false
        return Closeable { synchronized(this) {
            if (!closed) {
                closed = true
                if (protectFromPruning) {
                    val count = viewers.getValue(id) - 1
                    if (count == 0) viewers.remove(id) else viewers[id] = count
                    entries[id]?.let(::indexExpiry)
                }
                val count = (leases[id] ?: 1) - 1
                if (count == 0) { leases.remove(id); if (id in deleted) remove(id) } else leases[id] = count
            }
        } }
    }
    private fun put(entry: TranscriptEntry) {
        entries.put(entry.id, entry)?.let {
            ordered.remove(it.cursor)
            if (it.audioId != entry.audioId) unlink(it)
        }
        if (entry.id !in deleted) {
            ordered.put(entry.cursor, entry)
            entry.audioId?.let { byAudio.getOrPut(it) { linkedSetOf() }.add(entry.id) }
        }
        indexExpiry(entry); changes.value++
    }
    private fun indexExpiry(entry: TranscriptEntry) = expiry.put(entry.id, entry.retention,
        eligible = entry.id !in viewers && entry.id !in deleted)
    private fun remove(id: String) {
        val entry = entries[id] ?: return
        if (!entry.directory.deleteRecursively()) return
        unlink(entry)
        entries.remove(id); ordered.remove(entry.cursor); deleted.remove(id); expiry.remove(id); changes.value++
    }
    private fun unlink(entry: TranscriptEntry) {
        val audioId = entry.audioId ?: return
        byAudio[audioId]?.let { ids ->
            ids.remove(entry.id)
            if (ids.isEmpty()) byAudio.remove(audioId)
        }
    }
    private fun persist(entry: TranscriptEntry, discarded: Boolean = false) {
        val p = Properties().apply {
            setProperty("created", entry.created.toString()); setProperty("model", entry.model)
            entry.audioId?.let { setProperty("audioId", it) }
            entry.modelId?.let { setProperty("modelId", it) }
            setProperty("durationMs", entry.durationMs.toString()); setProperty("speakerLabels", entry.speakerLabels.toString())
            setProperty("discarded", discarded.toString())
            setProperty("recovered", entry.recovered.toString())
            HistoryMetadata.writeMark(this, entry.retention)
        }
        HistoryMetadata.write(File(entry.directory, "transcript.properties"), p)
    }
}
