package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.TranscriptSource
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryProvenanceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun recoveryOriginSurvivesSuccessfulRetryPinAndAcknowledgement() {
        val root = folder.newFolder()
        val history = RecordingHistory(root)
        val capture = history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        capture.append(shortArrayOf(1, 2)); capture.finish(true)
        history.completeRecovery(capture.entry.id, HistoryRetention.HOUR)
        history.setPinned(capture.entry.id, true, HistoryRetention.HOUR, "launch")
        history.dismiss(capture.entry.id)
        val restored = RecordingHistory(root).list().single()
        assertFalse(restored.needsRecovery)
        assertTrue(HistoryRow.from(restored).recovery)
        assertEquals("true", HistoryMetadata.read(File(restored.directory, "recording.properties")).getProperty("recovered"))
    }

    @Test fun recoveredTextSavesItsOwnOriginWithoutDependingOnAudio() {
        val source = folder.newFile("working.txt").apply { writeText("Recovered words") }
        TranscriptSource.metadata(source).writeText("recovered=true\n")
        val root = folder.newFolder()
        val saved = TranscriptHistory(root).save(source, "model", pinned = true)
        val restored = TranscriptHistory(root).list().single()
        assertEquals(saved.id, restored.id)
        assertTrue(HistoryRow.from(restored, "Recovered words").recovery)
        assertEquals("true", HistoryMetadata.read(File(restored.directory, "transcript.properties")).getProperty("recovered"))
    }

    @Test fun normalTemporaryOwnershipIsNotRecoveryOrigin() {
        val history = RecordingHistory(folder.root)
        val capture = history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        capture.append(shortArrayOf(1)); capture.finish(false)
        val entry = history.get(capture.entry.id)
        assertTrue(entry.needsRecovery)
        assertFalse(HistoryRow.from(entry).recovery)
        assertFalse(HistoryMetadata.read(File(entry.directory, "recording.properties")).getProperty("recovered", "false").toBoolean())
    }
}
