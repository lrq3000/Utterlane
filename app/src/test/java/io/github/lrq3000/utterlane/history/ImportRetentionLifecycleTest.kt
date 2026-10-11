package io.github.lrq3000.utterlane.history

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportRetentionLifecycleTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val metadata = AudioImportMetadata("original.opus", "audio/ogg", 600001, 4, "audio/opus", true)

    @Test fun retainedImportIsNormalUnpinnedAndSurvivesDismissalAndRestartUntilDue() {
        var now = 100L
        val history = RecordingHistory(folder.root) { now }
        val owned = history.importAudioOwned(bytes.inputStream(), "opus", metadata, ImportRetentionChoice.KEEP)
        val entry = owned.entry
        assertFalse(entry.temporary); assertFalse(entry.pinned); assertNull(entry.holdForLaunch)
        assertTrue(entry.needsRecovery); assertFalse(entry.recovered)
        history.completeRecovery(entry.id, HistoryRetention.HOUR)
        assertFalse(history.get(entry.id).needsRecovery)
        assertEquals(0, history.recoveryCount())
        history.dismiss(entry.id)
        owned.lease.close()
        val restarted = RecordingHistory(folder.root) { now }
        assertEquals(metadata, restarted.list().single().importMetadata)
        assertArrayEquals(bytes, restarted.get(entry.id).part(0).readBytes())
        assertFalse(restarted.get(entry.id).recovered)
        now += HistoryRetention.HOUR.millis
        restarted.prune(HistoryRetention.HOUR)
        assertTrue(restarted.list().isEmpty())
    }

    @Test fun protectedPublicationAndAllActiveOwnersDeferEvenImmediateExpiry() {
        val history = RecordingHistory(folder.root)
        val owned = history.importAudioOwned(bytes.inputStream(), "opus", metadata, ImportRetentionChoice.KEEP)
        history.prune(HistoryRetention.NONE) // model preparation
        assertTrue(owned.entry.part(0).exists())
        val viewer = history.acquire(owned.entry.id, protectFromPruning = true)
        history.completeRecovery(owned.entry.id, HistoryRetention.NONE)
        owned.lease.close()
        history.prune(HistoryRetention.NONE)
        assertEquals(1, history.list().size)
        viewer.close()
        history.prune(HistoryRetention.NONE)
        assertTrue(history.list().isEmpty())
    }

    @Test fun transcriptOnlySuccessCleansAtLastReleaseWithoutRecoveryOriginOrPermanentEntry() {
        val history = RecordingHistory(folder.root)
        val owned = history.importAudioOwned(bytes.inputStream(), "opus", metadata, ImportRetentionChoice.DISCARD)
        val reader = history.acquire(owned.entry.id)
        history.completeRecovery(owned.entry.id, HistoryRetention.HOUR)
        assertTrue(history.get(owned.entry.id).temporary)
        assertFalse(history.get(owned.entry.id).needsRecovery)
        assertFalse(history.get(owned.entry.id).recovered)
        owned.lease.close()
        assertTrue(owned.entry.part(0).exists())
        reader.close()
        assertFalse(owned.entry.directory.exists())
        assertTrue(history.list().isEmpty())
    }

    @Test fun completedTranscriptOnlyImportIsNotPromotedByChangedGlobalPreferenceOnExit() {
        val history = RecordingHistory(folder.root)
        val owned = history.importAudioOwned(bytes.inputStream(), "opus", metadata, ImportRetentionChoice.DISCARD)
        history.completeRecovery(owned.entry.id, HistoryRetention.HOUR)
        val policy = HistoryExitPolicy(true, HistoryRetention.FOREVER)
        assertFalse(policy.requiresConfirmation(history.get(owned.entry.id), 0))
        val otherOwner = history.acquire(owned.entry.id, protectFromPruning = true)
        assertTrue(history.retainOnExit(owned.entry.id, policy))
        owned.lease.close()
        assertEquals("Closing one view must not discard another owner's input", "saved", history.get(owned.entry.id).status)
        assertEquals(listOf(owned.entry.id), history.list().map { it.id })
        otherOwner.close()
        assertTrue(history.list().isEmpty())
    }

    @Test fun unfinishedOrFailedTranscriptOnlyInputStillRequiresExitConsentAndSurvivesOwnerLoss() {
        for (failed in listOf(false, true)) {
            val root = folder.newFolder()
            val history = RecordingHistory(root)
            val owned = history.importAudioOwned(bytes.inputStream(), "opus", metadata, ImportRetentionChoice.DISCARD)
            if (failed) history.recordFailure(owned.entry.id, "Model failed", "MODEL")
            val policy = HistoryExitPolicy(true, HistoryRetention.FOREVER)
            assertTrue(policy.requiresConfirmation(history.get(owned.entry.id), 0))
            assertFalse(history.retainOnExit(owned.entry.id, policy))
            owned.lease.close()
            val restarted = RecordingHistory(root)
            assertTrue(restarted.list().single().needsRecovery)
            assertTrue(restarted.list().single().recovered)
            assertTrue(restarted.retainOnExit(owned.entry.id, policy, discardIfNeeded = true))
            assertTrue(restarted.list().isEmpty())
        }
    }

    @Test fun explicitPinOverridesTranscriptOnlyChoiceButRetryRestoresPendingRecovery() {
        val history = RecordingHistory(folder.root)
        val owned = history.importAudioOwned(bytes.inputStream(), "opus", metadata, ImportRetentionChoice.DISCARD)
        history.completeRecovery(owned.entry.id, HistoryRetention.HOUR)
        assertFalse(history.get(owned.entry.id).needsRecovery)
        val attempt = history.beginProcessing(owned.entry.id)
        assertTrue(history.get(owned.entry.id).needsRecovery)
        attempt.fail("Failed retry", "INFERENCE")
        history.setPinned(owned.entry.id, true, HistoryRetention.HOUR, "launch")
        assertTrue(history.retainOnExit(owned.entry.id, HistoryExitPolicy(false, HistoryRetention.NONE)))
        owned.lease.close()
        assertTrue(history.get(owned.entry.id).pinned)
        assertTrue(history.get(owned.entry.id).recovered)
    }

    @Test fun processDeathAfterCommitCleansSuccessfulTranscriptOnlyBytesOnInitialization() {
        val history = RecordingHistory(folder.root)
        val owned = history.importAudioOwned(bytes.inputStream(), "opus", metadata, ImportRetentionChoice.DISCARD)
        history.completeRecovery(owned.entry.id, HistoryRetention.HOUR)
        // No runtime leases survive a real process death.
        assertTrue(RecordingHistory(folder.root).list().isEmpty())
        assertFalse(owned.entry.directory.exists())
    }

    @Test fun unknownMetadataStaysUnknownAcrossRestart() {
        val history = RecordingHistory(folder.root)
        val unknown = AudioImportMetadata()
        val owned = history.importAudioOwned(bytes.inputStream(), "audio", unknown, ImportRetentionChoice.KEEP)
        history.completeRecovery(owned.entry.id, HistoryRetention.HOUR)
        owned.lease.close()
        assertEquals(unknown, RecordingHistory(folder.root).list().single().importMetadata)
    }

    @Test fun emptyAndFailingCopiesNeverPublishOrDisturbPriorInput() {
        val history = RecordingHistory(folder.root)
        val prior = history.importAudioOwned(bytes.inputStream(), "opus", metadata, ImportRetentionChoice.KEEP)
        val streams = listOf(byteArrayOf().inputStream(), object : InputStream() {
            var calls = 0
            override fun read(): Int = if (calls++ < 2) 1 else throw IOException("Provider failed")
        })
        for (stream in streams) {
            assertThrows(Exception::class.java) {
                history.importAudioOwned(stream, "opus", metadata, ImportRetentionChoice.KEEP)
            }
            assertEquals(listOf(prior.entry.id), history.list().map { it.id })
            assertEquals(listOf(prior.entry.id), folder.root.list()!!.toList())
            assertArrayEquals(bytes, prior.entry.part(0).readBytes())
        }
        prior.lease.close()
    }

    @Test fun copyIsInvisibleUntilEofAndUncommittedRetainedInputRecoversWithinNormalRetention() {
        var now = 100L
        val history = RecordingHistory(folder.root) { now }
        val input = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(history.list().isEmpty())
                history.prune(HistoryRetention.NONE)
                return super.read(buffer, offset, length)
            }
        }
        val owned = history.importAudioOwned(input, "opus", metadata, ImportRetentionChoice.KEEP)
        owned.lease.close() // process loss before ASR commit, no explicit dismissal
        val restarted = RecordingHistory(folder.root) { now }
        restarted.prune(HistoryRetention.HOUR)
        val recovered = restarted.list().single()
        assertTrue(recovered.needsRecovery); assertTrue(recovered.recovered)
        assertEquals("interrupted", recovered.status)
        assertFalse(recovered.pinned)
        assertArrayEquals(bytes, recovered.part(0).readBytes())
        now += HistoryRetention.HOUR.millis
        restarted.prune(HistoryRetention.HOUR)
        assertTrue("Pending recovery is not an indefinite pin for ordinary retained imports", restarted.list().isEmpty())
    }
}
