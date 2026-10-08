package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.HistoryEntry
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.RecordingHistory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeCaptureCompletionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun allocatedIdDeletedAfterRecorderOpenFailureCannotReleasePriorUnsavedWork() = fixture().use { f ->
        val empty = f.history.begin(HistoryRetention.NONE, automatic = false, keepUntilDismissed = true)
        val allocatedId = empty.entry.id
        empty.finish(true)
        assertTrue(allocatedId.isNotBlank())
        assertThrows(IllegalStateException::class.java) { f.history.get(allocatedId) }
        f.events.captureEnded()
        f.deliver(audio = null, preview = "", message = "Recorder could not open")
        f.events.closed()
        f.assertPriorPreserved("Recorder could not open")
    }

    @Test fun cancellationBeforeReadyWithAnAllocatedButEmptySourcePreservesPriorWork() = fixture().use { f ->
        val empty = f.history.begin(HistoryRetention.NONE, automatic = false, keepUntilDismissed = true)
        f.owner.interrupt()
        empty.finish(true)
        f.deliver(audio = null, preview = "", message = "Cancelled before microphone opened")
        f.events.closed()
        f.assertPriorPreserved("Cancelled before microphone opened")
    }

    @Test fun allocatedEmptyMetadataAndWhitespaceTextAreNotUsefulInput() = fixture().use { f ->
        val empty = f.history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        try {
            f.deliver(empty.entry, " \n\t", "No input")
            f.assertPriorPreserved("No input")
        } finally { empty.finish(true) }
    }

    @Test fun discardedAudioCannotReplacePriorWorkEvenIfItsLeaseDefersFileDeletion() = fixture().use { f ->
        val recorded = f.history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recorded.append(shortArrayOf(7)); recorded.finish(true)
        f.history.acquire(recorded.entry.id).use {
            f.history.delete(recorded.entry.id)
            f.deliver(f.history.get(recorded.entry.id), "", "Audio unavailable")
            f.assertPriorPreserved("Audio unavailable")
        }
    }

    @Test fun retainedSamplesBeforeReadyAreStillAdoptedForRecovery() = fixture().use { f ->
        val recorded = f.history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recorded.append(shortArrayOf(7)); recorded.finish(true)
        assertTrue(f.deliver(f.history.get(recorded.entry.id), "", "Recognition failed"))
        assertEquals(1, f.releases)
        assertEquals(listOf("new result"), f.results)
    }

    @Test fun usefulTextIsAdoptedEvenWhenItsSourceAudioIsUnavailable() = fixture().use { f ->
        assertTrue(f.deliver(null, "Useful partial transcript", "Audio unavailable"))
        assertEquals(1, f.releases)
        assertEquals(listOf("new result"), f.results)
    }

    private fun fixture() = Fixture(RecordingHistory(folder.newFolder()), TranscriptStore(folder.newFile()))

    private class Fixture(val history: RecordingHistory, private val priorText: TranscriptStore) : java.io.Closeable {
        private val priorAudio = history.begin(HistoryRetention.NONE, automatic = false, keepUntilDismissed = true).also {
            it.append(shortArrayOf(1, 2)); it.finish(false)
        }
        lateinit var events: HomeCaptureOwner.Events<String>
        var releases = 0
        val results = mutableListOf<String>()
        val owner = HomeCaptureOwner<String>(factory = { callbacks ->
            events = callbacks
            object : HomeCaptureOwner.Driver {
                override fun start() = Unit
                override fun stop() = Unit
                override fun interrupt() = Unit
            }
        }, onAccepted = {
            releases++
            history.dismiss(priorAudio.entry.id)
            priorText.dispose()
        }, onResult = results::add)
        init { priorText.append("Prior unsaved words"); owner.start() }
        fun deliver(audio: HistoryEntry?, preview: String, message: String) =
            HomeCaptureCompletion<String>(events::result, events::rejected).deliver("new result", audio, preview, message)
        fun assertPriorPreserved(message: String) {
            assertEquals(0, releases)
            assertTrue(results.isEmpty())
            assertEquals("Prior unsaved words", priorText.file.readText())
            assertEquals(2L, history.get(priorAudio.entry.id).samples)
            assertEquals(message, owner.state.value.message)
            assertFalse(owner.state.value.active)
        }
        override fun close() { priorText.dispose(); history.dismiss(priorAudio.entry.id) }
    }
}
