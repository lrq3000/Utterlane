package io.github.lrq3000.utterlane.history

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.TreeSet
import java.util.UUID

data class HistoryEntry(val id: String, val directory: File, val started: Long, val reference: Long, val samples: Long, val status: String) {
    val seconds: Long get() = samples / 16000
    val parts: Int get() = ((samples + RecordingHistory.PART_SAMPLES - 1) / RecordingHistory.PART_SAMPLES).toInt()
    fun part(index: Int) = File(directory, "audio-$index.wav")
}

/** Private recording index and leases. All callers perform filesystem work on IO. */
class RecordingHistory(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object { const val PART_SAMPLES = 3600L * 16000 }
    private val entries = mutableMapOf<String, HistoryEntry>()
    private val ordered = TreeSet(compareByDescending<HistoryEntry> { it.started }.thenBy { it.id })
    private val active = mutableMapOf<String, Recording>()
    private val leases = mutableMapOf<String, Int>()
    private val deferred = mutableSetOf<String>()
    private var initialized = false

    @Synchronized fun initialize() {
        if (initialized) return
        check(root.mkdirs() || root.isDirectory) { "Cannot open recording history" }
        root.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val metadata = File(directory, "recording.properties")
            if (!metadata.exists()) return@forEach
            val properties = Properties().apply { metadata.inputStream().use { load(it) } }
            var samples = properties.getProperty("samples", "0").toLong()
            var status = properties.getProperty("status", "interrupted")
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
            val entry = HistoryEntry(directory.name, directory, properties.getProperty("started", "0").toLong(), reference, samples, status)
            put(entry)
            if (status == "interrupted") save(entry)
        }
        initialized = true
    }

    @Synchronized fun begin(retention: HistoryRetention): Recording? {
        if (retention == HistoryRetention.NONE) return null
        initialize()
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        check(directory.mkdir()) { "Cannot create recording history" }
        val entry = HistoryEntry(id, directory, clock(), clock(), 0, "active")
        save(entry)
        put(entry)
        return Recording(entry).also { active[id] = it; leases[id] = 1 }
    }

    @Synchronized fun list(page: Int = 0, pageSize: Int = 30): List<HistoryEntry> =
        ordered.asSequence().filter { it.status != "active" && it.id !in deferred }.drop(page * pageSize).take(pageSize).toList()

    @Synchronized fun get(id: String): HistoryEntry = checkNotNull(entries[id]) { "Recording is unavailable" }

    @Synchronized fun acquire(id: String): Closeable {
        check(id in entries && id !in deferred) { "Recording expired or was deleted" }
        leases[id] = (leases[id] ?: 0) + 1
        var closed = false
        return Closeable { synchronized(this) { if (!closed) { closed = true; release(id) } } }
    }

    @Synchronized fun prune(retention: HistoryRetention) {
        initialize()
        entries.values.filter { it.id !in active && retention.expired(it.reference, clock()) }.map { it.id }.forEach { delete(it) }
    }

    @Synchronized fun delete(id: String) {
        if (id !in entries) return
        deferred.add(id)
        if ((leases[id] ?: 0) == 0) remove(id)
    }

    @Synchronized fun read(id: String, offset: Long, count: Int): ShortArray {
        require(offset >= 0 && count in 0..3200)
        val entry = checkNotNull(entries[id]) { "Recording is unavailable" }
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

    private fun put(entry: HistoryEntry) {
        entries.put(entry.id, entry)?.let { ordered.remove(it) }
        ordered.add(entry)
    }
    private fun release(id: String) {
        val count = (leases[id] ?: 1) - 1
        if (count == 0) { leases.remove(id); if (id in deferred) remove(id) } else leases[id] = count
    }
    private fun remove(id: String) {
        val entry = entries[id] ?: return
        // Keep the index/deferred marker if deletion fails, so the next prune retries.
        if (!entry.directory.deleteRecursively()) return
        entries.remove(id); ordered.remove(entry); deferred.remove(id)
    }
    private fun save(entry: HistoryEntry) {
        val properties = Properties().apply {
            setProperty("started", entry.started.toString()); setProperty("reference", entry.reference.toString())
            setProperty("samples", entry.samples.toString()); setProperty("status", entry.status)
        }
        val temporary = File(entry.directory, "recording.tmp")
        temporary.outputStream().use { properties.store(it, "Microphone recording") }
        Files.move(temporary.toPath(), File(entry.directory, "recording.properties").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    inner class Recording internal constructor(val entry: HistoryEntry) {
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
            try { wav?.close() } finally {
                synchronized(this@RecordingHistory) {
                    val completed = entry.copy(reference = clock(), samples = writtenSamples, status = if (failed) "failed" else "saved")
                    try { save(completed) } finally { put(completed); active.remove(entry.id); release(entry.id) }
                }
            }
        }
    }
}
