package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.archive.ArchiveDisk
import io.github.lrq3000.utterlane.archive.AudioMetadata
import io.github.lrq3000.utterlane.history.TranscriptHistory
import java.io.File
import java.util.Properties
import java.util.UUID

internal enum class QueueStage { WAITING, PROCESSING, CAPTURING, DRAINING, READY, FAILED, PAUSING, PAUSED, STOPPING, STOPPED;
    val reviewable get() = this == READY || this == FAILED || this == STOPPED
    val needsProcessingService get() = this == WAITING || this == PROCESSING || this == DRAINING || this == PAUSING || this == STOPPING
}

internal data class QueueRecord(val id: String, val sequence: Long, val audio: String, val label: String,
    val speakers: Boolean, val stage: QueueStage, val message: String? = null, val modelId: String? = null, val terminalPosted: Boolean = false,
    val resumeSamples: Long = 0, val checkpointed: Boolean = false, val speakerSection: Int = 0) {
    val textId: String get() = TranscriptHistory.idForAttempt("$id.txt")
    // PAUSING precedes the offset commit. A fresh waiting/paused file has not selected any representation yet.
    val holdsOriginal get() = stage == QueueStage.PROCESSING || stage == QueueStage.PAUSING ||
        checkpointed && (stage == QueueStage.PAUSED || stage == QueueStage.WAITING)
    // Other services and recovery notifications use positive IDs. Android foreground IDs must be nonzero.
    val notificationId: Int get() = -sequence.toInt()
    /** A fresh diarizer cannot identify people from a retired session. Scope later speaker numbers to their section instead of implying cross-pause identity. */
    fun continued() = copy(stage = QueueStage.WAITING, message = null,
        speakerSection = if (speakers && checkpointed && resumeSamples > 0L) speakerSection + 1 else speakerSection)
}

/** Each descriptor is independently replaceable. It contains no PCM or transcript body. Histories only query the lookup indexes; the journal never calls a history while holding its lock. */
internal class QueueJournal(private val root: File) {
    private val jobs = LinkedHashMap<String, QueueRecord>()
    private val audioRefs = HashMap<String, Int>()
    private val originalRefs = HashMap<String, Int>()
    private val releases = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val originalReleases: kotlinx.coroutines.flow.StateFlow<Long> = releases
    private val texts = HashSet<String>()
    private var loaded = false
    private var sequence = 0L
    private fun index(row: QueueRecord) {
        jobs[row.id] = row
        audioRefs[row.audio] = (audioRefs[row.audio] ?: 0) + 1
        if (row.holdsOriginal) retainOriginal(row.audio)
        texts.add(row.textId)
        sequence = maxOf(sequence, row.sequence)
    }
    private fun load() {
        if (loaded) return
        val read = root.listFiles().orEmpty().filter { it.extension == "job" }.map { file ->
            val p = ArchiveDisk.read(file)
            QueueRecord(file.nameWithoutExtension, p.getProperty("order").toLong(), p.getProperty("audio"),
                p.getProperty("label"), p.getProperty("speakers").toBoolean(), QueueStage.valueOf(p.getProperty("stage")), p.getProperty("message"), p.getProperty("model"),
                p.getProperty("terminalPosted").toBoolean(), p.getProperty("resumeSamples", "0").toLong(), p.getProperty("checkpointed").toBoolean(),
                p.getProperty("speakerSection", "0").toInt())
        }.sortedBy { it.sequence }
        read.forEach(::index)
        loaded = true
    }
    private fun write(row: QueueRecord) = AudioMetadata.write(File(root, "${row.id}.job"), Properties().apply {
        setProperty("order", row.sequence.toString()); setProperty("audio", row.audio); setProperty("label", row.label)
        setProperty("speakers", row.speakers.toString()); setProperty("stage", row.stage.name)
        row.message?.let { setProperty("message", it) }
        row.modelId?.let { setProperty("model", it) }
        if (row.terminalPosted) setProperty("terminalPosted", "true")
        if (row.checkpointed) setProperty("checkpointed", "true")
        if (row.resumeSamples != 0L) setProperty("resumeSamples", row.resumeSamples.toString())
        if (row.speakerSection != 0) setProperty("speakerSection", row.speakerSection.toString())
    })
    @Synchronized fun records(): List<QueueRecord> { load(); return jobs.values.toList() }
    @Synchronized fun find(id: String): QueueRecord? { load(); return jobs[id] }
    @Synchronized fun protectsAudio(id: String): Boolean { load(); return id in audioRefs }
    @Synchronized fun protectsOriginal(id: String): Boolean { load(); return id in originalRefs }
    private fun retainOriginal(audio: String) { originalRefs[audio] = (originalRefs[audio] ?: 0) + 1 }
    private fun releaseOriginal(audio: String) {
        val remaining = originalRefs.getValue(audio) - 1
        if (remaining == 0) {
            originalRefs.remove(audio)
            releases.value++ // Wake private cleanup even if a stopped row remains unreviewed and no further history writes occur.
        } else originalRefs[audio] = remaining
    }
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
        if (row.holdsOriginal != old.holdsOriginal) {
            if (row.holdsOriginal) retainOriginal(row.audio) else releaseOriginal(row.audio)
        }
    }
    /** A stale selection, finalizer or double tap must not overwrite a newer disposition of this job. */
    @Synchronized fun transition(id: String, from: Set<QueueStage>, change: (QueueRecord) -> QueueRecord): QueueRecord? =
        find(id)?.takeIf { it.stage in from }?.let(change)?.also(::update)
    @Synchronized fun finishCapture(id: String, failure: String?, interrupted: String, speakers: Boolean?): QueueRecord? {
        val row = find(id) ?: return null
        if (row.stage != QueueStage.CAPTURING && row.stage != QueueStage.DRAINING) return null
        // Missing capture-ended means there was no normal hardware/EOF handoff, even if teardown supplied no detailed error.
        val reason = failure ?: interrupted.takeIf { row.stage == QueueStage.CAPTURING }
        return row.copy(stage = if (reason == null) QueueStage.READY else QueueStage.FAILED,
            message = reason, speakers = speakers ?: row.speakers).also(::update)
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
