package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Transfer contracts shared by the dialog and Home, independently of the visible page. */
class TranscriptStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun transferIncludesTheOffscreenPrefixWhilePreviewRemainsBounded() = withStore { store ->
        val fullText = "Offscreen beginning " + "spoken words ".repeat(1000) + "visible ending"
        store.append(fullText)
        assertEquals(TranscriptStore.PREVIEW_LIMIT, store.preview().length)
        assertFalse(store.preview().contains("Offscreen beginning"))
        assertEquals(fullText, store.readForTransfer())
    }

    @Test fun transferLimitCountsUtf8BytesAndNeverReturnsAPartialTranscript() = withStore { store ->
        val fullText = "🙂".repeat(TranscriptStore.TRANSFER_LIMIT / 4)
        store.append(fullText)
        assertEquals(TranscriptStore.TRANSFER_LIMIT.toLong(), store.file.length())
        assertEquals(fullText, store.readForTransfer())
        store.append("é")
        // The UI must offer export, not silently copy the preview or truncate
        // at a character count that exceeds the actual byte transfer budget.
        assertNull(store.readForTransfer())
        assertEquals("$fullText é", store.snapshot().readText(Charsets.UTF_8))
    }

    private fun withStore(check: (TranscriptStore) -> Unit) {
        val store = TranscriptStore(directory.newFile("transcript.txt"))
        try { check(store) } finally { store.dispose() }
    }
}
