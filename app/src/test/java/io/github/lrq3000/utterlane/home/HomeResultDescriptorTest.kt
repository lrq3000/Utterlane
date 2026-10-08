package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.history.TranscriptMetadata
import io.github.lrq3000.utterlane.transcribe.DialogInput
import org.junit.Assert.*
import org.junit.Test

class HomeResultDescriptorTest {
    private val captured = DialogInput(audioId = "audio", transcriptPath = "/cache/transcripts/captured.txt",
        transcriptOrigin = false, modelName = "model", modelId = "model-id", metadata = TranscriptMetadata(123L, 4500L, true))

    @Test fun unsavedCapturePathSurvivesTheInitialEmptyDialogSnapshot() {
        val owner = HomeResultDescriptor(captured)
        val checkpoint = owner.update(captured.copy(transcriptPath = null), importing = true)
        assertEquals(captured, checkpoint)
        // Simulate another process reconstruction before the first owner has
        // hydrated: the next startup must still know where the user's text is.
        val restarted = HomeResultDescriptor(checkpoint)
        assertEquals(captured, restarted.update(checkpoint.copy(transcriptPath = null), importing = true))
    }

    @Test fun partiallyHydratedSavedTranscriptRetainsAllUnresolvedIdentifiers() {
        val input = captured.copy(transcriptId = "saved-text")
        val owner = HomeResultDescriptor(input)
        assertEquals(input, owner.update(input.copy(audioId = null, transcriptId = null, transcriptPath = null), importing = true))
        assertEquals(input, owner.update(input.copy(transcriptPath = null), importing = true))
        assertEquals(input, owner.update(input, importing = false))
    }

    @Test fun failedInitializationDoesNotTurnAnUnreadStoreIntoExplicitDeletion() {
        val owner = HomeResultDescriptor(captured)
        // The model's finally block clears importing even if opening text failed.
        val failed = owner.update(captured.copy(transcriptPath = null), importing = false)
        assertEquals(captured.transcriptPath, failed.transcriptPath)
        assertEquals(captured.metadata, failed.metadata)
    }

    @Test fun completedHydrationAllowsRetryToReplaceTextAndClearItsOldSavedId() {
        val input = captured.copy(transcriptId = "saved-text")
        val owner = HomeResultDescriptor(input)
        owner.update(input, importing = false)
        val retry = input.copy(transcriptPath = "/cache/transcripts/retry.txt", transcriptId = null,
            modelId = "replacement-model", metadata = input.metadata!!.copy(speakerLabels = false))
        assertEquals(retry, owner.update(retry, importing = false))
        // Once initialized, nulls are authoritative, not permanent fallbacks to
        // the input. Explicit release must never resurrect an earlier descriptor.
        val cleared = retry.copy(audioId = null, transcriptId = null, transcriptPath = null)
        assertEquals(cleared, owner.update(cleared, importing = false))
    }

    @Test fun aValidWorkingCopyResolvesAMissingSavedHistoryEntry() {
        val input = captured.copy(transcriptId = "expired")
        val owner = HomeResultDescriptor(input)
        val resolved = input.copy(transcriptId = null)
        assertEquals(resolved, owner.update(resolved, importing = false))
    }

    @Test fun audioOnlyImportPublishesItsNewOwnedSourceWithoutInventingText() {
        val owner = HomeResultDescriptor(DialogInput())
        val imported = DialogInput(audioId = "new-audio", transcriptOrigin = false)
        assertEquals(imported, owner.update(imported, importing = true))
        assertEquals(imported, owner.update(imported, importing = false))
        assertNull(owner.update(DialogInput(), importing = false).audioId)
    }
}
