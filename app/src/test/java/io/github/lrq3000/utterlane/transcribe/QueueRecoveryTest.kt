package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.history.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueueRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun resetAfterPreviewWaitsForLateRegistrationThenUnblocksQueuedModelChange() = runBlocking {
        val journal = QueueJournal(temp.root)
        val id = UUID.randomUUID().toString()
        val registered = CompletableDeferred<Unit>()
        val finish = launch(start = CoroutineStart.UNDISPATCHED) {
            afterCapturePublication(registered, null) { journal.finishCapture(id, "Model reset", "Interrupted", true) }
        }
        assertFalse(finish.isCompleted)
        journal.add("retained", "Live", false, QueueStage.CAPTURING, id)
        registered.complete(Unit)
        finish.join()
        val restored = QueueJournal(temp.root)
        val row = restored.find(id)!!
        assertEquals(QueueStage.FAILED, row.stage)
        assertEquals("Model reset", row.message)
        assertTrue(row.speakers)
        assertTrue(restored.protectsAudio("retained"))
        assertFalse(restored.records().any { it.stage == QueueStage.CAPTURING || it.stage == QueueStage.DRAINING })
        assertNull(restored.finishCapture(id, null, "late callback", false))
    }

    @Test fun failedRetryWithoutWorkingTextOpensWithItsOwnModelAndSpeakerRequest() {
        val queue = QueueJournal(temp.root)
        val retry = queue.add("audio", "Retry", true, modelId = "previous-model")
        queue.update(retry.copy(stage = QueueStage.FAILED))
        val input = QueueJournal(temp.root).find(retry.id)!!.reviewInput(null, null, null)
        assertEquals("previous-model", input.modelId)
        assertEquals(true, input.fileOptions?.speakerLabels)
        assertNull(input.transcriptPath)
    }
    @Test fun crashBeforeStopPreservesFirstPartialAfterHomeMovesToSecondRecording() {
        val root = File(temp.root, "history")
        val first = RecordingHistory(root).begin(HistoryRetention.NONE, automatic = false, keepUntilDismissed = true)
        first.append(ShortArray(8000)); first.closeCapture()
        val job = UUID.randomUUID().toString()
        val path = File(temp.root, "$job.txt")
        val store = TranscriptStore(path)
        store.beginTranscription(TranscriptSource(first.entry.id))
        store.append("before interruption"); store.finishProducing(); store.keepForRecovery()
        val checkpoint = DialogInput(audioId = first.entry.id, transcriptPath = path.path, queueId = job)
        val journal = QueueJournal(File(temp.root, "jobs"))
        val restored = RecordingHistory(root).apply { awaitingQueueReview = journal::protectsAudio }
        QueueRecovery(journal, restored, { File(temp.root, "$it.txt") }).restore(checkpoint, "Recording", "Interrupted")
        journal.add("second-source", "Next", false)
        restored.prune(HistoryRetention.NONE)
        val previous = requireNotNull(journal.find(job))
        assertEquals(QueueStage.FAILED, previous.stage)
        assertEquals(first.entry.id, previous.audio)
        assertEquals("before interruption", path.readText())
        assertTrue(restored.get(previous.audio).part(0).exists())
    }
}
