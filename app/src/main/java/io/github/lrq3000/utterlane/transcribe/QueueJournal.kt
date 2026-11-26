package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.archive.ArchiveDisk
import io.github.lrq3000.utterlane.archive.AudioMetadata
import io.github.lrq3000.utterlane.history.TranscriptHistory
import java.io.File
import java.util.Properties
import java.util.UUID

internal enum class QueueStage { WAITING, PROCESSING, CAPTURING, DRAINING, READY, FAILED;
    val reviewable get() = this == READY || this == FAILED
    val needsProcessingService get() = this == WAITING || this == PROCESSING || this == DRAINING
}

internal data class QueueRecord(val id: String, val sequence: Long, val audio: String, val label: String,
    val speakers: Boolean, val stage: QueueStage, val message: String? = null, val modelId: String? = null) {
    val textId: String get() = TranscriptHistory.idForAttempt("$id.txt")
    // Other services and recovery notifications use positive IDs. Android foreground IDs must be nonzero.
    val notificationId: Int get() = -sequence.toInt()
}

/** Each descriptor is independently replaceable. It contains no PCM or transcript body. Histories only query the two lookup indexes; the journal never calls a history while holding its lock. */
internal class QueueJournal(private val root: File) {
    private val jobs = LinkedHashMap<String, QueueRecord>()
    private val audioRefs = HashMap<String, Int>()
    private val texts = HashSet<String>()
    private var loaded = false
    private var sequence = 0L
    private fun index(row: QueueRecord) {
        jobs[row.id] = row
        audioRefs[row.audio] = (audioRefs[row.audio] ?: 0) + 1
        texts.add(row.textId)
        sequence = maxOf(sequence, row.sequence)
    }
    private fun load() {
        if (loaded) return
        val read = root.listFiles().orEmpty().filter { it.extension == "job" }.map { file ->
            val p = ArchiveDisk.read(file)
            QueueRecord(file.nameWithoutExtension, p.getProperty("order").toLong(), p.getProperty("audio"),
                p.getProperty("label"), p.getProperty("speakers").toBoolean(), QueueStage.valueOf(p.getProperty("stage")), p.getProperty("message"), p.getProperty("model"))
        }.sortedBy { it.sequence }
        read.forEach(::index)
        loaded = true
    }
    private fun write(row: QueueRecord) = AudioMetadata.write(File(root, "${row.id}.job"), Properties().apply {
        setProperty("order", row.sequence.toString()); setProperty("audio", row.audio); setProperty("label", row.label)
        setProperty("speakers", row.speakers.toString()); setProperty("stage", row.stage.name)
        row.message?.let { setProperty("message", it) }
        row.modelId?.let { setProperty("model", it) }
    })
    @Synchronized fun records(): List<QueueRecord> { load(); return jobs.values.toList() }
    @Synchronized fun find(id: String): QueueRecord? { load(); return jobs[id] }
    @Synchronized fun protectsAudio(id: String): Boolean { load(); return id in audioRefs }
    @Synchronized fun anotherOwnsAudio(audio: String, reviewing: String?): Boolean {
        load()
        return (audioRefs[audio] ?: 0) > if (jobs[reviewing]?.audio == audio) 1 else 0
    }
    @Synchronized fun protectsText(id: String): Boolean { load(); return id in texts }
    @Synchronized fun add(audio: String, label: String, speakers: Boolean, stage: QueueStage = QueueStage.WAITING,
        id: String = UUID.randomUUID().toString(), modelId: String? = null): QueueRecord {
        load()
        require(UUID.fromString(id).toString() == id && id !in jobs)
        check(sequence < Int.MAX_VALUE)
        val row = QueueRecord(id, sequence + 1, audio, label, speakers, stage, modelId = modelId)
        write(row); index(row)
        return row
    }
    @Synchronized fun update(row: QueueRecord) {
        load()
        val old = jobs.getValue(row.id)
        require(old.sequence == row.sequence && old.audio == row.audio)
        write(row); jobs[row.id] = row
    }
    @Synchronized fun acknowledge(id: String): Boolean {
        load()
        val row = jobs[id]?.takeIf { it.stage.reviewable } ?: return false
        ArchiveDisk.remove(File(root, "$id.job"))
        AudioMetadata.syncDirectory(root)
        jobs.remove(id); texts.remove(row.textId)
        val remaining = audioRefs.getValue(row.audio) - 1
        if (remaining == 0) audioRefs.remove(row.audio) else audioRefs[row.audio] = remaining
        return true
    }
}
