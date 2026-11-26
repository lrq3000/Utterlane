package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.transcribe.QueueRecord
import io.github.lrq3000.utterlane.transcribe.QueueStage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueueHistoryIndexTest {
    @get:Rule val temp = TemporaryFolder()
    private fun attempt(n: Long, stage: QueueStage) = QueueRecord(
        "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}", n, "same-recording", "lecture.wav", false, stage
    )

    @Test fun pausedAndUnreviewedAttemptsStayReachableWithoutShowingAnIdleHomeMonitor() {
        val paused = attempt(1, QueueStage.PAUSED)
        val stopped = attempt(2, QueueStage.STOPPED)
        val ready = attempt(3, QueueStage.READY)
        val index = QueueHistoryIndex(listOf(paused, stopped, ready))
        assertNull(index.monitorJob)
        assertEquals(listOf(paused, stopped, ready), index.withoutHistoryRows(emptySet()))
    }

    @Test fun alreadySavedAttemptUsesItsOwnJobAndAnUnrelatedTranscriptStaysIndependent() {
        val original = attempt(1, QueueStage.STOPPED)
        val retry = attempt(2, QueueStage.PROCESSING)
        val paused = attempt(3, QueueStage.PAUSED)
        val index = QueueHistoryIndex(listOf(original, retry, paused))
        val otherText = "some-other-transcript-from-the-same-audio"
        assertEquals(listOf(paused), index.withoutHistoryRows(setOf(original.textId, retry.textId, otherText)))
        assertEquals(original.id, index.jobForText(original.textId)?.id)
        assertEquals(retry.id, index.jobForText(retry.textId)?.id)
        assertNull(index.jobForText(otherText))
    }

    @Test fun keptStoppedAttemptReopensItsOwnJobAfterColdStartWithoutDuplicatingItsRetry() {
        val stopped = attempt(1, QueueStage.STOPPED)
        val retry = attempt(2, QueueStage.PROCESSING)
        val rows = listOf(stopped, retry)
        val historyRoot = temp.newFolder()
        val resultRoot = temp.newFolder()
        fun target(id: String) = File(resultRoot, "$id.txt")
        val store = TranscriptStore(target(stopped.id))
        val kept = try {
            store.append("partial from the original attempt")
            TranscriptHistory(historyRoot).saveWorking(store, "Model", stopped.audio, null, TranscriptMetadata())
        } finally { store.keepForRecovery() }
        assertTrue(kept.retention.pinned)
        assertNotEquals(stopped.textId, kept.id)
        val coldHistory = TranscriptHistory(historyRoot)
        val index = QueueHistoryIndex.fromProvenance(rows, coldHistory, ::target)
        assertEquals(stopped.id, index.jobForText(kept.id)?.id)
        assertEquals(retry.id, index.jobForText(retry.textId)?.id)
        assertEquals(listOf(retry), index.withoutHistoryRows(setOf(kept.id)))
        assertEquals(listOf(retry, stopped), index.withoutHistoryRows(emptySet()))
        coldHistory.delete(kept.id)
        assertNull(QueueHistoryIndex.fromProvenance(rows, coldHistory, ::target).jobForText(kept.id))
        assertEquals("Pruning the saved row does not change the producer target's provenance", kept.id, stopped.provenanceAlias(::target))
    }

    @Test fun monitorDescribesWorkInProgressBeforeWaitingAndNeverPicksAReviewOrPause() {
        val waiting = attempt(2, QueueStage.WAITING)
        val draining = attempt(3, QueueStage.DRAINING)
        val index = QueueHistoryIndex(listOf(attempt(1, QueueStage.FAILED), waiting, draining, attempt(4, QueueStage.PAUSED)))
        assertEquals(draining, index.monitorJob)
        assertEquals(2, index.monitorCount)
        assertEquals(waiting, QueueHistoryIndex(listOf(waiting)).monitorJob)
        assertEquals(QueueStage.CAPTURING, QueueHistoryIndex(listOf(attempt(5, QueueStage.CAPTURING))).monitorJob?.stage)
    }

    @Test fun unopenedReviewsCannotBuryUnfinishedWorkInTheQueueList() {
        val earlierReview = attempt(1, QueueStage.READY)
        val paused = attempt(2, QueueStage.PAUSED)
        val stopped = attempt(3, QueueStage.STOPPED)
        val running = attempt(4, QueueStage.PROCESSING)
        val index = QueueHistoryIndex(listOf(earlierReview, paused, stopped, running))
        assertEquals(listOf(paused, running), index.monitorRows)
        assertEquals(listOf(paused, running, earlierReview, stopped), index.queueRows)
        assertEquals(listOf(paused, running, earlierReview), index.withoutHistoryRows(setOf(stopped.textId)))
    }

    @Test fun fileCancellationIsNotOfferedOnCaptureOrItsLiveDrain() {
        assertFalse(QueueStage.CAPTURING.canCancelFromList)
        assertFalse(QueueStage.DRAINING.canCancelFromList)
        assertTrue(QueueStage.PAUSED.canCancelFromList)
        assertTrue(QueueStage.PROCESSING.canCancelFromList)
        assertTrue(QueueStage.WAITING.canCancelFromList)
        assertTrue(QueueStage.READY.canCancelFromList)
        assertTrue(QueueStage.STOPPED.canCancelFromList)
    }
}
