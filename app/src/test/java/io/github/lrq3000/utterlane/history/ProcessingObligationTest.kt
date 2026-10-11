package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessingObligationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun imported(history: RecordingHistory) = history.importAudioOwned(byteArrayOf(1, 2, 3).inputStream(),
        "wav", AudioImportMetadata(), ImportRetentionChoice.DISCARD)

    @Test fun firstAttemptSuccessCannotAcknowledgeSecondAttemptAcrossRestart() {
        val history = RecordingHistory(folder.root)
        val audio = imported(history)
        val a = history.beginProcessing(audio.entry.id)
        history.beginProcessing(audio.entry.id)
        a.complete()
        val restarted = RecordingHistory(folder.root)
        val surviving = restarted.list().singleOrNull()
        assertNotNull("The unfinished second attempt must survive loss of every runtime lease", surviving)
        assertTrue(surviving!!.needsRecovery)
        assertTrue(surviving.recovered)
        assertArrayEquals(byteArrayOf(1, 2, 3), surviving.part(0).readBytes())
    }

    @Test fun siblingFailureOrCancellationCannotBeAcknowledgedByAnEarlierConcurrentSuccess() {
        for (failure in listOf(false, true)) for (finishSiblingFirst in listOf(false, true)) {
            val root = folder.newFolder()
            val history = RecordingHistory(root)
            val audio = imported(history)
            val a = history.beginProcessing(audio.entry.id)
            val b = history.beginProcessing(audio.entry.id)
            if (finishSiblingFirst) a.complete()
            if (failure) b.fail("Model failed", "MODEL") else b.close()
            if (!finishSiblingFirst) a.complete()
            audio.lease.close()
            assertTrue("Another attempt's success cannot release failed/cancelled input", audio.entry.part(0).isFile)
            val restarted = RecordingHistory(root)
            assertTrue(restarted.list().single().needsRecovery)
            assertTrue(restarted.list().single().recovered)
            if (failure) assertEquals("Model failed", restarted.list().single().failureMessage)
        }
    }

    @Test fun allSuccessfulAttemptsAllowCleanupOnlyAfterTheLastAudioLease() {
        val history = RecordingHistory(folder.root)
        val audio = imported(history)
        val otherView = history.acquire(audio.entry.id, protectFromPruning = true)
        val a = history.beginProcessing(audio.entry.id)
        val b = history.beginProcessing(audio.entry.id)
        a.complete()
        assertTrue(history.get(audio.entry.id).needsRecovery)
        b.complete()
        assertFalse(history.get(audio.entry.id).needsRecovery)
        assertFalse(history.get(audio.entry.id).recovered)
        a.close(); b.close() // Completion and finally-close are idempotent.
        audio.lease.close()
        assertTrue(audio.entry.part(0).isFile)
        otherView.close()
        assertFalse(audio.entry.directory.exists())
    }

    @Test fun aRetrySettlesAbandonedProcessingAfterRestartWithoutAnIndefiniteTemporaryCopy() {
        val history = RecordingHistory(folder.root)
        val audio = imported(history)
        history.beginProcessing(audio.entry.id)
        history.beginProcessing(audio.entry.id)
        val restarted = RecordingHistory(folder.root)
        val entry = restarted.list().single()
        restarted.acquire(entry.id, protectFromPruning = true).use {
            val retry = restarted.beginProcessing(entry.id)
            retry.complete(); retry.close()
            assertFalse(restarted.get(entry.id).needsRecovery)
        }
        assertTrue(restarted.list().isEmpty())
        assertFalse(entry.directory.exists())
    }

    @Test fun retryClaimsOnlyAlreadyAbandonedWorkNotAnotherLiveAttempt() {
        val history = RecordingHistory(folder.root)
        val audio = imported(history)
        val live = history.beginProcessing(audio.entry.id)
        val failed = history.beginProcessing(audio.entry.id)
        failed.fail("Failed attempt", "INFERENCE")
        val retry = history.beginProcessing(audio.entry.id)
        retry.complete()
        assertTrue("Retry cannot settle a still-running sibling", history.get(audio.entry.id).needsRecovery)
        live.close()
        audio.lease.close()
        val restarted = RecordingHistory(folder.root)
        assertTrue(restarted.list().single().needsRecovery)
    }

    @Test fun legacyCompletionAndNormalExitCannotClearTrackedProcessing() {
        val history = RecordingHistory(folder.root)
        val audio = history.importAudioOwned(byteArrayOf(1).inputStream(), "wav", AudioImportMetadata(), ImportRetentionChoice.KEEP)
        val attempt = history.beginProcessing(audio.entry.id)
        history.completeRecovery(audio.entry.id, HistoryRetention.HOUR)
        assertTrue(history.get(audio.entry.id).needsRecovery)
        history.retainOnExit(audio.entry.id, HistoryExitPolicy(true, HistoryRetention.HOUR))
        assertTrue(history.get(audio.entry.id).needsRecovery)
        history.dismiss(audio.entry.id)
        assertTrue(history.get(audio.entry.id).needsRecovery)
        attempt.complete()
        assertFalse(history.get(audio.entry.id).needsRecovery)
        audio.lease.close()
    }

    @Test fun explicitDeletionWinsAgainstAllLateAttemptCallbacks() {
        val history = RecordingHistory(folder.root)
        val audio = imported(history)
        val a = history.beginProcessing(audio.entry.id)
        val b = history.beginProcessing(audio.entry.id)
        history.delete(audio.entry.id)
        a.complete(); b.fail("Late failure", "MODEL"); a.close(); b.close()
        assertTrue(history.list().isEmpty())
        audio.lease.close()
        assertFalse(audio.entry.directory.exists())
    }

    @Test fun repeatedUnsuccessfulRetriesTransferOneObligationAndIgnoreOldCallbacks() {
        val history = RecordingHistory(folder.root)
        val audio = imported(history)
        val old = history.beginProcessing(audio.entry.id)
        old.fail("First failure", "MODEL")
        repeat(30) {
            val retry = history.beginProcessing(audio.entry.id)
            assertEquals(setOf(retry.token), history.get(audio.entry.id).processingAttempts)
            old.complete(); old.close()
            assertEquals(setOf(retry.token), history.get(audio.entry.id).processingAttempts)
            retry.close()
        }
        val final = history.beginProcessing(audio.entry.id)
        final.complete()
        assertTrue(history.get(audio.entry.id).processingAttempts.isEmpty())
        audio.lease.close()
        val remains = audio.entry.directory.exists()
        assertFalse(if (remains) "After final lease: leased=${history.hasLeases(audio.entry.id)}, " +
            "entry=${runCatching { history.get(audio.entry.id) }.getOrNull()}, " +
            "files=${audio.entry.directory.list()?.toList()}" else "Audio owner was released", remains)
    }

    @Test fun completionMetadataFailurePreservesDurableObligationAndLetsLaterRetryClaimIt() {
        val history = RecordingHistory(folder.root)
        val audio = imported(history)
        val attempt = history.beginProcessing(audio.entry.id)
        val obstruction = java.io.File(audio.entry.directory, "recording.properties.tmp")
        assertTrue(obstruction.mkdir())
        try { assertThrows(java.io.IOException::class.java) { attempt.complete() } }
        finally { obstruction.delete() }
        assertTrue(history.get(audio.entry.id).needsRecovery)
        val retry = history.beginProcessing(audio.entry.id)
        assertEquals(setOf(retry.token), history.get(audio.entry.id).processingAttempts)
        retry.complete()
        audio.lease.close()
        assertFalse(audio.entry.directory.exists())
    }
}
