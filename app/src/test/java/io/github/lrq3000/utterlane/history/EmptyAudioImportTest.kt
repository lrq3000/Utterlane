package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmptyAudioImportTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun emptyProviderDoesNotPublishAnOwnedReplacementOrLeaveImportArtifacts() {
        val root = folder.newFolder()
        val history = RecordingHistory(root)
        val prior = history.importAudio(byteArrayOf(1, 2, 3).inputStream(), "wav", "audio/wav")
        assertThrows(IllegalStateException::class.java) {
            history.importAudio(byteArrayOf().inputStream(), "wav", "audio/wav")
        }
        assertEquals(listOf(prior.id), history.list().map { it.id })
        assertEquals(listOf(prior.id), root.list()!!.toList())
        assertArrayEquals(byteArrayOf(1, 2, 3), prior.part(0).readBytes())
    }
}
