package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogState
import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeFileHandoffTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun importingCandidateKeepsPriorWorkspaceAndCheckpointWhileBusy() = Fixture().use { f ->
        f.model.value = TranscriptionDialogState(importing = true, progress = 10)
        assertEquals("prior working text", f.home.value.result.preview)
        assertEquals("prior checkpoint", f.checkpoint)
        assertTrue(f.home.value.busy)
        assertEquals(0, f.accepted)
        assertTrue(f.job.isActive)
    }

    @Test fun missingOrRevokedSelectionReportsErrorWithoutAcceptingOrChangingPriorData() = Fixture().use { f ->
        f.model.value = TranscriptionDialogState(message = "Provider access revoked")
        assertEquals(0, f.accepted)
        assertEquals(listOf("Provider access revoked"), f.rejected)
        assertEquals("prior working text", f.home.value.result.preview)
        assertEquals("prior checkpoint", f.checkpoint)
        assertFalse(f.home.value.busy)
        assertTrue(f.job.isCompleted)
    }

    @Test fun zeroByteOwnedEntryIsNotReplacementInput() = Fixture().use { f ->
        val file = folder.newFolder()
        java.io.File(file, "empty.wav").createNewFile()
        f.model.value = TranscriptionDialogState(audio = HistoryEntry("empty", file, 1, 1, 0, "ready",
            temporary = true, sourceName = "empty.wav"), message = "Empty source")
        assertEquals(0, f.accepted)
        assertEquals(listOf("Empty source"), f.rejected)
        assertEquals("prior checkpoint", f.checkpoint)
    }

    @Test fun partialCopyMustFinishBeforeItCanReplacePriorWork() = Fixture().use { f ->
        val directory = folder.newFolder()
        java.io.File(directory, "copy.wav").writeBytes(byteArrayOf(1, 2, 3))
        val copying = HistoryEntry("partial", directory, 1, 1, 0, "importing", temporary = true, sourceName = "copy.wav")
        f.model.value = TranscriptionDialogState(audio = copying, importing = true)
        assertEquals(0, f.accepted)
        assertEquals("prior checkpoint", f.checkpoint)
        f.model.value = TranscriptionDialogState(message = "Provider failed midway")
        assertEquals(0, f.accepted)
        assertEquals(listOf("Provider failed midway"), f.rejected)
    }

    @Test fun missingOwnedPayloadCannotBeAcceptedFromMetadataAlone() = Fixture().use { f ->
        val history = RecordingHistory(folder.newFolder())
        val audio = history.importAudio(byteArrayOf(1).inputStream(), "wav", "audio/wav")
        audio.part(0).delete()
        f.model.value = TranscriptionDialogState(audio = audio, message = "Source disappeared")
        assertEquals(0, f.accepted)
        assertEquals(listOf("Source disappeared"), f.rejected)
    }

    @Test fun rejectionDoesNotReleasePreparationAheadOfOwnerCleanup() {
        val root = SupervisorJob()
        val scope = CoroutineScope(root + Dispatchers.Unconfined)
        val home = MutableStateFlow(HomeState(preparing = true, result = TranscriptionDialogState(preview = "old")))
        val model = MutableStateFlow(TranscriptionDialogState(importing = true))
        var cleaning = false
        try {
            val job = HomeFileHandoff(scope, Dispatchers.Unconfined, Dispatchers.Unconfined).observe(model, { true },
                onAccepted = { fail("A failed source must not be adopted") }, onRejected = { cleaning = true })
            model.value = TranscriptionDialogState(message = "No source")
            assertTrue(cleaning)
            assertTrue(job.isCompleted)
            assertTrue("The owner decides when cleanup releases its preparation hold", home.value.busy)
            assertEquals("old", home.value.result.preview)
            home.update { it.copy(preparing = false) }
            assertFalse(home.value.busy)
        } finally { root.cancel() }
    }

    @Test fun nonemptyCorruptSourceIsAcceptedEvenAfterDecodingFails() = Fixture().use { f ->
        val history = RecordingHistory(folder.newFolder())
        val audio = history.importAudio(byteArrayOf(1, 2, 3).inputStream(), "wav", "audio/wav")
        f.model.value = TranscriptionDialogState(audio = audio, message = "Decoder rejected the format")
        assertEquals(1, f.accepted)
        assertEquals(audio, f.home.value.result.audio)
        assertEquals("Decoder rejected the format", f.home.value.result.message)
        assertEquals("candidate checkpoint", f.checkpoint)
        assertTrue(f.rejected.isEmpty())
        assertTrue(f.job.isCompleted)
        f.model.value = f.model.value.copy(progress = 100)
        assertEquals("A terminal observer must not retire the prior owner twice", 1, f.accepted)
    }

    @Test fun usefulCommittedTextCanAcceptWithoutSurvivingAudio() = Fixture().use { f ->
        val store = TranscriptStore(folder.newFile()).apply { append("New useful text") }
        try {
            f.model.value = TranscriptionDialogState(store = store, preview = store.preview(), transcriptBytes = store.bytes)
            assertEquals(1, f.accepted)
            assertSame(store, f.home.value.result.store)
        } finally { store.dispose() }
    }

    @Test fun discardedWorkingTextIsNotReplacementInput() = Fixture().use { f ->
        val store = TranscriptStore(folder.newFile()).apply { append("Discarded candidate") }
        try {
            TranscriptStore.deleteArtifacts(store.file)
            f.model.value = TranscriptionDialogState(store = store, preview = store.preview(), transcriptBytes = store.bytes,
                message = "Deleted elsewhere")
            assertEquals(0, f.accepted)
            assertEquals(listOf("Deleted elsewhere"), f.rejected)
        } finally { store.keepForRecovery() }
    }

    @Test fun acceptancePublishesLatestRunningStateAndReleasesPreparationAtomically() {
        val io = QueuedDispatcher()
        Fixture(io).use { f ->
            io.drain()
            val audio = RecordingHistory(folder.newFolder()).importAudio(byteArrayOf(1).inputStream(), "wav", "audio/wav")
            f.model.value = TranscriptionDialogState(audio = audio, importing = true)
            // Import completion/ASR starts while candidate validation is queued.
            f.model.value = f.model.value.copy(importing = false, running = true)
            io.drain()
            assertEquals(1, f.accepted)
            assertTrue(f.home.value.result.running)
            assertFalse(f.home.value.preparing)
            assertTrue("An immediate FGS observer must never see a false idle gap", f.observed.all { it.busy })
        }
    }

    @Test fun staleOrCancelledCandidateCannotReplacePriorWorkspace() {
        val io = QueuedDispatcher()
        Fixture(io).use { f ->
            f.current = false
            io.drain()
            assertEquals(0, f.accepted)
            assertTrue(f.rejected.isEmpty())
            assertEquals("prior checkpoint", f.checkpoint)
        }
        Fixture(io).use { f ->
            f.job.cancel()
            io.drain()
            assertEquals(0, f.accepted)
            assertTrue(f.rejected.isEmpty())
        }
    }

    private class Fixture(io: CoroutineDispatcher = Dispatchers.Unconfined) : Closeable {
        val home = MutableStateFlow(HomeState(result = TranscriptionDialogState(preview = "prior working text"), preparing = true))
        val model = MutableStateFlow(TranscriptionDialogState(importing = true))
        val observed = mutableListOf<HomeState>()
        var checkpoint = "prior checkpoint"
        var current = true
        var accepted = 0
        val rejected = mutableListOf<String?>()
        private val root = SupervisorJob()
        private val scope = CoroutineScope(root + Dispatchers.Unconfined)
        val job: Job
        init {
            scope.launch { home.collect { observed.add(it) } }
            job = HomeFileHandoff(scope, Dispatchers.Unconfined, io).observe(model, { current }, onAccepted = {
                accepted++
                checkpoint = "candidate checkpoint"
                HomeFileHandoff.publish(home, null, model)
            }, onRejected = { message ->
                rejected.add(message)
                home.update { it.copy(preparing = false, message = message) }
            })
        }
        override fun close() { root.cancel() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
