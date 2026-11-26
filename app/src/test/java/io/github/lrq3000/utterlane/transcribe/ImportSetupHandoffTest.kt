package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.history.AudioImportMetadata
import io.github.lrq3000.utterlane.history.ImportRetentionCategory
import org.junit.Assert.*
import org.junit.Test

class ImportSetupHandoffTest {
    private val receipt = ImportReceipt("batch", 2, 2, 2, "first", false, emptyMap(), null, false)
    private val finished = ImportScreenState(receipt, loaded = true)
    private val waiting = listOf(QueueRecord("first", 1, "audio", "Memo", false, QueueStage.WAITING))
    private val running = QueueServiceAdmission.Status.RUNNING

    @Test fun aBackgroundRequestDoesNotDismissStagingConsentErrorsOrSkippedFiles() {
        val question = ImportRetentionRequest(ImportRetentionCategory.SIZE, AudioImportMetadata())
        val pending = receipt.copy(cursor = 1, accepted = 1, uri = "content://audio/2")
        for (state in listOf(ImportScreenState(), finished.copy(receipt = pending, busy = true),
            finished.copy(receipt = pending, question = question), finished.copy(receipt = receipt.copy(accepted = 1)),
            finished.copy(problem = ImportScreenState.Problem.STORAGE))) {
            assertEquals(ImportSetupHandoff.STAY, state.handoff(background = true, service = running, jobs = waiting))
        }
    }

    @Test fun waitForServiceAdmissionThenHonorTheIndependentlySelectedDestination() {
        assertEquals(ImportSetupHandoff.STAY, finished.handoff(true, running, emptyList()))
        for (status in listOf(QueueServiceAdmission.Status.STOPPED, QueueServiceAdmission.Status.REQUESTED))
            assertEquals(ImportSetupHandoff.STAY, finished.handoff(true, status, waiting))
        assertEquals(ImportSetupHandoff.BACKGROUND, finished.handoff(true, running, waiting))
        assertEquals(ImportSetupHandoff.OPEN, finished.handoff(false, running, waiting))
        val ready = waiting.map { it.copy(stage = QueueStage.READY) }
        assertEquals(ImportSetupHandoff.BACKGROUND, finished.handoff(true, QueueServiceAdmission.Status.STOPPED, ready))
    }

    @Test fun doNotHideAKnownFailureOfTheInitialResultOrConfuseItWithAnotherJobsError() {
        val failed = waiting.single().copy(stage = QueueStage.FAILED)
        assertEquals(ImportSetupHandoff.STAY, finished.handoff(true, running, listOf(failed)))
        assertEquals(ImportSetupHandoff.OPEN, finished.handoff(false, running, listOf(failed)))
        assertEquals(ImportSetupHandoff.BACKGROUND, finished.handoff(true, running, waiting + failed.copy(id = "unrelated")))
    }
}
