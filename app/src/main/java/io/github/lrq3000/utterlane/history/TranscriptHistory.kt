package io.github.lrq3000.utterlane.history

import java.io.Closeable
import java.io.File
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TranscriptEntry(val id: String, val directory: File, val created: Long, val model: String,
    val audioId: String?, val retention: RetentionMark, val modelId: String? = null) {
    val file get() = File(directory, "transcript.txt")
    val cursor get() = HistoryCursor(created, id)
}

/** Text history owns independent copies; deleting source audio cannot cascade into it. */
class TranscriptHistory(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = mutableMapOf<String, TranscriptEntry>()
    private val ordered = HistoryIndex<TranscriptEntry>()
    private val expiry = RetentionIndex()
    private val leases = mutableMapOf<String, Int>()
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
                p.getProperty("model", ""), p.getProperty("audioId"), HistoryMetadata.readMark(p, created), p.getProperty("modelId")))
        }
        initialized = true
    }

    /** A stable attempt ID deduplicates manual saving; a new recognition attempt uses a new ID. */
    @Synchronized fun save(source: File, model: String, audioId: String? = null, pinned: Boolean = false,
        attempt: String = source.name, modelId: String? = null): TranscriptEntry {
        initialize()
        val id = UUID.nameUUIDFromBytes(attempt.toByteArray(Charsets.UTF_8)).toString()
        entries[id]?.let { existing ->
            check(id !in deleted) { "Transcript was deleted" }
            if (pinned && !existing.retention.pinned) setPinned(id, true, HistoryRetention.FOREVER, "")
            return get(id)
        }
        check(source.isFile && source.length() > 0) { "There is no transcript to save" }
        val directory = File(root, id)
        check(directory.mkdir()) { "Cannot create transcript history" }
        val entry = TranscriptEntry(id, directory, clock(), model, audioId, RetentionMark(clock(), pinned), modelId)
        try {
            source.inputStream().use { input -> entry.file.outputStream().use { input.copyTo(it, 64 * 1024) } }
            persist(entry)
            put(entry)
            return entry
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }

    @Synchronized fun get(id: String): TranscriptEntry = checkNotNull(entries[id]?.takeIf { id !in deleted }) { "Transcript is unavailable" }
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
        val entry = get(id)
        val updated = entry.copy(retention = entry.retention.pin(value, duration, clock(), launch))
        persist(updated); put(updated)
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
        deleted.add(id); ordered.remove(entry.cursor); expiry.remove(id); changes.value++
        if ((leases[id] ?: 0) == 0) remove(id)
    }
    @Synchronized fun acquire(id: String): Closeable {
        get(id)
        leases[id] = (leases[id] ?: 0) + 1
        var closed = false
        return Closeable { synchronized(this) {
            if (!closed) {
                closed = true
                val count = (leases[id] ?: 1) - 1
                if (count == 0) { leases.remove(id); if (id in deleted) remove(id) } else leases[id] = count
            }
        } }
    }
    private fun put(entry: TranscriptEntry) {
        entries.put(entry.id, entry)?.let { ordered.remove(it.cursor) }
        if (entry.id !in deleted) ordered.put(entry.cursor, entry)
        expiry.put(entry.id, entry.retention); changes.value++
    }
    private fun remove(id: String) {
        val entry = entries[id] ?: return
        if (!entry.directory.deleteRecursively()) return
        entries.remove(id); ordered.remove(entry.cursor); deleted.remove(id); expiry.remove(id); changes.value++
    }
    private fun persist(entry: TranscriptEntry, discarded: Boolean = false) {
        val p = Properties().apply {
            setProperty("created", entry.created.toString()); setProperty("model", entry.model)
            entry.audioId?.let { setProperty("audioId", it) }
            entry.modelId?.let { setProperty("modelId", it) }
            setProperty("discarded", discarded.toString())
            HistoryMetadata.writeMark(this, entry.retention)
        }
        HistoryMetadata.write(File(entry.directory, "transcript.properties"), p)
    }
}
