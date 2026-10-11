package io.github.lrq3000.utterlane.archive

import java.io.File
import java.util.Properties

enum class ArtifactKind { AUDIO, TRANSCRIPT }
data class ArtifactOwner(val kind: ArtifactKind, val id: String)
object AudioRepresentation { const val ORIGINAL = "original" }
enum class ArchivalOperation { COPY_AUDIO, SAVE_MARKDOWN, ENCODE_AUDIO }
enum class ArchivalState { QUEUED, RUNNING, READY, PUBLISHED, PAUSED, CANCELLED }
/** Retry scheduling is independent of what remains to do. A failed continuous
 * codec can only finish its parent-side fallback, never restart that stream. */
enum class ArchivalPhase { PRIMARY, PENDING_FALLBACK }
data class ArchivalRequest(val owner: ArtifactOwner, val operation: ArchivalOperation,
    val source: File, val destination: String, val displayName: String, val mimeType: String,
    val part: Int = 0, val created: Long = 0, val model: String = "",
    val representation: String = AudioRepresentation.ORIGINAL,
    val sourceRepresentation: String = AudioRepresentation.ORIGINAL)
data class ArchivalJob(val id: String, val request: ArchivalRequest, val epoch: Long = 0,
    val generation: Long = 0, val state: ArchivalState = ArchivalState.QUEUED,
    val uri: String? = null, val error: String? = null, val retryPass: Long = -1,
    val phase: ArchivalPhase = ArchivalPhase.PRIMARY) {
    val targetName get() = "${request.displayName.substringBeforeLast('.')}__${id}_$generation.${request.displayName.substringAfterLast('.', "bin")}"
}
data class ArchiveSubmission(val id: String, val state: ArchivalState)

/** One target/creation intent per file. Retries cannot grow an in-memory set in
 * a job descriptor. key identifies private metadata, never a provider path. */
data class ArchivalCleanup(val jobId: String, val id: String, val generation: Long,
    val owner: ArtifactOwner, val destination: String, val slot: String,
    val uri: String? = null, val name: String? = null, val lastPass: Long = -1,
    val waitingForLease: Boolean = false) {
    val key get() = "$jobId~$id"
}

internal object ArchiveRecords {
    val jobKeys = setOf("kind", "owner", "operation", "source", "destination", "name", "mime", "part", "created", "model",
        "epoch", "generation", "state", "uri", "error", "retryPass", "representation", "sourceRepresentation", "phase")
    fun job(id: String, p: Properties): ArchivalJob = ArchivalJob(id,
        ArchivalRequest(p.owner(), ArchivalOperation.valueOf(p.getProperty("operation")), File(p.getProperty("source")),
            p.getProperty("destination"), p.getProperty("name"), p.getProperty("mime"), p.getProperty("part").toInt(),
            p.getProperty("created").toLong(), p.getProperty("model"), p.getProperty("representation") ?:
                if (p.getProperty("operation") == ArchivalOperation.ENCODE_AUDIO.name) "encoded-$id" else AudioRepresentation.ORIGINAL,
            p.getProperty("sourceRepresentation", AudioRepresentation.ORIGINAL)), p.getProperty("epoch").toLong(),
        p.getProperty("generation").toLong(), ArchivalState.valueOf(p.getProperty("state")), p.getProperty("uri"), p.getProperty("error"),
        p.getProperty("retryPass", "-1").toLong(), ArchivalPhase.valueOf(p.getProperty("phase", ArchivalPhase.PRIMARY.name)))
    fun job(job: ArchivalJob) = Properties().apply {
        val r = job.request
        putOwner(r.owner); setProperty("operation", r.operation.name); setProperty("source", r.source.absolutePath)
        setProperty("destination", r.destination); setProperty("name", r.displayName); setProperty("mime", r.mimeType)
        setProperty("part", r.part.toString()); setProperty("created", r.created.toString()); setProperty("model", r.model)
        setProperty("representation", r.representation); setProperty("sourceRepresentation", r.sourceRepresentation)
        setProperty("epoch", job.epoch.toString()); setProperty("generation", job.generation.toString()); setProperty("state", job.state.name)
        job.uri?.let { setProperty("uri", it) }; job.error?.let { setProperty("error", it) }
        setProperty("retryPass", job.retryPass.toString())
        setProperty("phase", job.phase.name)
    }
    fun target(ref: ArchivalCleanup) = Properties().apply {
        putOwner(ref.owner); setProperty("job", ref.jobId); setProperty("id", ref.id); setProperty("generation", ref.generation.toString())
        setProperty("destination", ref.destination); setProperty("slot", ref.slot); setProperty("pass", ref.lastPass.toString())
        setProperty("lease", ref.waitingForLease.toString())
        ref.uri?.let { setProperty("uri", it) }; ref.name?.let { setProperty("name", it) }
    }
    fun target(p: Properties) = ArchivalCleanup(p.getProperty("job"), p.getProperty("id"), p.getProperty("generation").toLong(),
        p.owner(), p.getProperty("destination"), p.getProperty("slot"), p.getProperty("uri"), p.getProperty("name"),
        p.getProperty("pass", "-1").toLong(), p.getProperty("lease", "false").toBoolean())
}
