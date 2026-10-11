package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.TranscriptEntry
import io.github.lrq3000.utterlane.history.TranscriptHistory
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import java.io.Closeable
import kotlinx.coroutines.*

/** One selected version, including its chronology/provenance, never a late read
 * of a dialog that may already be showing a different recognition attempt. */
data class MarkdownSelection(val store: TranscriptStore, val model: String, val audioId: String?,
    val modelId: String?, val metadata: TranscriptMetadata)

class PreparedMarkdownSave(val plan: ArchivePlan, private val release: () -> Unit) : Closeable {
    override fun close() = release()
}

data class MarkdownSaveResult(val selection: MarkdownSelection, val entry: TranscriptEntry, val state: ArchivalState) {
    fun canUpdate(current: TranscriptStore?, displayed: TranscriptStore?): Boolean =
        current === selection.store && displayed === selection.store
}

/** Application-owned publication; no global result-busy flag and no wait for
 * external writing. Preparation may suspend, so selection and leasing are local. */
class ManualMarkdownSave(private val scope: CoroutineScope, private val history: TranscriptHistory,
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    fun start(selection: MarkdownSelection, prepare: suspend () -> PreparedMarkdownSave,
        onPublished: (MarkdownSaveResult) -> Unit, onFailure: (Exception) -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var lease: Closeable? = null
            try {
                lease = selection.store.acquire()
                prepare().use { prepared ->
                    val result = withContext(io) {
                        history.withPublicationLock {
                            val source = TranscriptSource.read(selection.store.file)
                            check(!source.discarded) { "Transcript was deleted" }
                            val existing = history.find(source.transcriptId ?: TranscriptHistory.idForAttempt(selection.store.file.name))
                            val saved = existing ?: history.saveWorking(selection.store, selection.model, selection.audioId, selection.modelId, selection.metadata)
                            val submitted = history.planArchive(saved.id, prepared.plan, retryPaused = true)
                            check(submitted?.state in setOf(ArchivalState.QUEUED, ArchivalState.RUNNING, ArchivalState.READY, ArchivalState.PUBLISHED)) {
                                "Markdown is paused or could not be queued; private text is preserved"
                            }
                            MarkdownSaveResult(selection, saved, checkNotNull(submitted).state)
                        }
                    }
                    onPublished(result)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { onFailure(e)
            } finally { withContext(NonCancellable + io) { lease?.close() } }
        }
}
