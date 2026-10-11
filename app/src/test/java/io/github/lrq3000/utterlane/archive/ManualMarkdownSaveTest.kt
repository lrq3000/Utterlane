package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.asr.CacheArtifacts
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.TranscriptHistory
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualMarkdownSaveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `delayed save keeps selected bytes and provenance without replacing retried result identity`() = runBlocking {
        val work = temp.newFolder()
        val old = TranscriptStore(File(work, "old.txt")).apply {
            append("Speaker 1: old complete words")
            attachSource(TranscriptSource("old-audio", modelName = "Old actual model", modelId = "old-model"))
        }
        val journal = ArchivalJobStore(temp.newFolder())
        val history = TranscriptHistory(temp.newFolder(), journal)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var current = old
        var displayed = old
        var uiId = "old-ui"
        var releaseCount = 0
        val selected = MarkdownSelection(old, old.source.modelName, old.source.audioId, old.source.modelId,
            TranscriptMetadata(created = 1234, durationMs = 5678, speakerLabels = true))
        val job = ManualMarkdownSave(this, history).start(selected, prepare = {
            entered.complete(Unit); resume.await()
            PreparedMarkdownSave(ArchivePlan("content://p/tree/folder", journal.currentEpoch())) { releaseCount++ }
        }, onPublished = { result -> if (result.canUpdate(current, displayed)) uiId = result.entry.id }, onFailure = { throw it })
        entered.await()
        val replacement = TranscriptStore(File(work, "new.txt")).apply {
            append("New result bytes"); attachSource(TranscriptSource("new-audio", modelName = "New model", modelId = "new-model"))
        }
        try {
            current = replacement; displayed = replacement; uiId = "new-ui"
            old.keepForRecovery()
            CacheArtifacts.prune(work, maximumAgeMs = 0)
            assertTrue("Click must acquire its lease before preparation suspends", old.file.exists())
            resume.complete(Unit); job.join()
            val saved = history.list().single()
            assertEquals("Speaker 1: old complete words", saved.file.readText())
            assertEquals("Old actual model", saved.model)
            assertEquals("old-model", saved.modelId)
            assertEquals("old-audio", saved.audioId)
            assertEquals(1234L, saved.created)
            assertEquals(5678L, saved.durationMs)
            assertTrue(saved.speakerLabels)
            assertEquals("new-ui", uiId)
            assertSame(replacement, current)
            assertEquals(1, releaseCount)
            CacheArtifacts.deleteWhenReleased(old.file)
            assertFalse("The request must release its selected-file lease", old.file.exists())
        } finally { resume.complete(Unit); job.cancelAndJoin(); old.keepForRecovery(); replacement.dispose() }
    }

    @Test fun `repeat manual save requeues its paused publication and a cancelled producer cannot do so`() = runBlocking {
        val store = TranscriptStore(temp.newFile()).apply { append("Complete selected text") }
        val journal = ArchivalJobStore(temp.newFolder())
        val history = TranscriptHistory(temp.newFolder(), journal)
        val selection = MarkdownSelection(store, "Actual model", null, "model-id", TranscriptMetadata(created = 100))
        val plan = ArchivePlan("content://p/tree/folder", journal.currentEpoch())
        val operation = ManualMarkdownSave(this, history)
        var result: MarkdownSaveResult? = null
        suspend fun save() = operation.start(selection, { PreparedMarkdownSave(plan) {} }, { result = it }, { throw it }).join()
        try {
            save()
            val firstOwner = checkNotNull(result).entry.id
            val job = checkNotNull(journal.claim())
            journal.notCreated(job.id, job.generation)
            journal.pause(job.id, job.generation, "Folder unavailable")
            journal.releaseExecution(job.id, job.generation)
            save()
            assertEquals(firstOwner, result?.entry?.id)
            assertEquals(ArchivalState.QUEUED, result?.state)
            val retry = checkNotNull(journal.claim())
            assertEquals(job.id, retry.id)
            assertTrue(retry.generation > job.generation)
            journal.notCreated(retry.id, retry.generation)
            journal.cancelAll(); journal.releaseExecution(retry.id, retry.generation)
            var failed = false
            operation.start(selection, { PreparedMarkdownSave(plan) {} }, { fail("Cancelled producer reported queued") }, { failed = true }).join()
            assertTrue(failed)
            assertEquals(0, journal.summary().queued)
            assertEquals("Complete selected text", history.get(firstOwner).file.readText())
        } finally { store.dispose(); journal.close() }
    }
}
