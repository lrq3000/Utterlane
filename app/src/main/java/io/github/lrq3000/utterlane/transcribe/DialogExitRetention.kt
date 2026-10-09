package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.flow.first

/** Only threatened identities belong to the confirmation. Recheck after joining
 * the producer: a new attempt or newly expired kind needs fresh consent. */
data class DialogExitRequest(val audioId: String? = null, val transcriptId: String? = null) {
    val hasLoss get() = audioId != null || transcriptId != null
    fun covers(other: DialogExitRequest) =
        (other.audioId == null || audioId == other.audioId) &&
            (other.transcriptId == null || transcriptId == other.transcriptId)
}

/** IO-only policy/publication boundary. The ViewModel owns operation cancellation
 * and navigation, while repositories retain authority over IDs and deletion. */
internal class DialogExitRetention(private val app: UtterlaneApp) {
    private val linked = LinkedHistory(app.recordingHistory, app.transcriptHistory)
    private data class Policies(val audio: HistoryExitPolicy, val text: HistoryExitPolicy)
    private suspend fun policies() = Policies(
        HistoryExitPolicy(app.settingsRepository.audioHistoryEnabled.first(), app.settingsRepository.audioHistoryRetention.first()),
        HistoryExitPolicy(app.settingsRepository.transcriptHistoryEnabled.first(), app.settingsRepository.transcriptHistoryRetention.first()))

    private fun saved(store: TranscriptStore): TranscriptEntry? {
        val source = TranscriptSource.read(store.file)
        return app.transcriptHistory.find(source.transcriptId ?: TranscriptHistory.idForAttempt(store.file.name))
    }
    private fun useful(store: TranscriptStore?) = store?.takeIf {
        it.file.isFile && it.bytes > 0 && !TranscriptSource.read(it.file).discarded
    }
    private fun loss(audioId: String?, store: TranscriptStore?, policy: Policies): DialogExitRequest {
        val now = System.currentTimeMillis()
        val audio = linked.availableAudio(audioId)
        val text = useful(store)
        val entry = text?.let(::saved)
        val source = text?.let { TranscriptSource.read(it.file) }
        val keepText = text == null || if (entry != null) policy.text.keeps(entry.retention, stored = true, now)
            // A missing saved identity may have been pruned or explicitly deleted.
            // Only explicit Pin may create a replacement; navigation cannot revive it.
            else source?.transcriptId == null && policy.text.keeps(RetentionMark(text.file.lastModified()), stored = false, now)
        return DialogExitRequest(
            audio?.takeUnless { policy.audio.keeps(it.retention, stored = !it.temporary, now) }?.id,
            if (keepText) null else source?.transcriptId ?: text?.let { TranscriptHistory.idForAttempt(it.file.name) })
    }

    suspend fun check(audioId: String?, store: TranscriptStore?): DialogExitRequest = loss(audioId, store, policies())

    /** Returns a renewed question without deleting anything if scope grew.
     * Text is published before releasing any source input. Partial save failures
     * may preserve extra work, but can never destroy the only surviving copy. */
    suspend fun finish(audioId: String?, store: TranscriptStore?, metadata: TranscriptMetadata,
        consent: DialogExitRequest?): DialogExitRequest? {
        val policy = policies()
        return app.transcriptHistory.withPublicationLock {
            val fresh = loss(audioId, store, policy)
            if (fresh.hasLoss && consent?.covers(fresh) != true) return@withPublicationLock fresh
            val text = useful(store)
            val existing = text?.let(::saved)
            if (text != null && fresh.transcriptId == null && existing == null) {
                val source = TranscriptSource.read(text.file)
                val entry = app.transcriptHistory.save(text.file, source.modelName, source.audioId ?: audioId,
                    modelId = source.modelId, created = metadata.created, durationMs = metadata.durationMs,
                    speakerLabels = metadata.speakerLabels, recovered = metadata.recovered || source.recovered,
                    reference = text.file.lastModified())
                text.attachSource(source.copy(transcriptId = entry.id, recovered = entry.recovered))
            }
            if (audioId != null && !app.recordingHistory.retainOnExit(audioId, policy.audio,
                    discardIfNeeded = consent?.audioId == audioId)) {
                return@withPublicationLock fresh.copy(audioId = audioId)
            }
            // Explicit deletion can still remove any entry; a view lease only
            // defers automatic expiry. Touch no sibling transcript identities.
            if (fresh.transcriptId != null) existing?.let { app.transcriptHistory.delete(it.id) }
            null
        }
    }
}
