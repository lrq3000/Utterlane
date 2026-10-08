package io.github.lrq3000.utterlane.history

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryRowTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun transcriptProjectionKeepsItsOwnRecordedMetadataAfterAudioChanges() {
        val audio = RecordingHistory(folder.newFolder()) { 100L }
        val capture = audio.begin(HistoryRetention.FOREVER)
        capture.append(ShortArray(32000)); capture.finish(false)
        val root = folder.newFolder()
        val text = TranscriptHistory(root) { 900L }
        val entry = text.save(folder.newFile().apply { writeText("\nFirst words\nLater words") }, "Model",
            audioId = capture.entry.id, created = 100, durationMs = 2000, speakerLabels = true, pinned = true)
        // A later recognition of the same audio must not rewrite the old result's
        // row metadata, even after process recreation or source-audio deletion.
        audio.setSpeakerLabels(capture.entry.id, false)
        audio.delete(capture.entry.id)
        val restored = TranscriptHistory(root).list().single()
        val row = HistoryRow.from(restored, restored.file.reader().use(HistoryPreview::read))
        assertEquals(entry.id, row.id)
        assertEquals(100L, row.cursor.created)
        assertEquals(2000L, row.durationMs)
        assertTrue(row.speakerLabels)
        assertTrue(row.retention.pinned)
        assertEquals("First words", row.detail)
    }

    @Test fun legacyTranscriptProjectionKeepsUnknownDurationAndAbsentLabels() {
        val directory = folder.newFolder("legacy")
        File(directory, "transcript.properties").writeText("created=10\nmodel=Old model\naudioId=audio\n")
        File(directory, "transcript.txt").writeText("Old words")
        val row = HistoryRow.from(TranscriptHistory(folder.root).list().single(), "Old words")
        assertEquals(0L, row.durationMs)
        assertFalse(row.speakerLabels)
        assertEquals(10L, row.cursor.created)
    }

    @Test fun audioProjectionKeepsDurationLabelsAndRecoveryWithoutInventingATitle() {
        val history = RecordingHistory(folder.newFolder()) { 100L }
        val recording = history.begin(HistoryRetention.FOREVER)
        recording.append(ShortArray(24000)); recording.finish(true)
        history.setSpeakerLabels(recording.entry.id, true)
        val row = HistoryRow.from(history.get(recording.entry.id))
        assertEquals(1500L, row.durationMs)
        assertTrue(row.speakerLabels)
        assertTrue(row.recovery)
        assertEquals("", row.detail)
    }
}
