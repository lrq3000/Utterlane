package io.github.lrq3000.utterlane.history

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryResultMetadataTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun transcriptUsesSourceChronologyButRetentionStartsAtSaveTime() {
        var now = 1000L
        val root = folder.newFolder()
        val history = TranscriptHistory(root) { now }
        val source = folder.newFile().apply { writeText("Speaker 1: words") }
        val entry = history.save(source, "Model", "audio", created = 100L, durationMs = 1234, speakerLabels = true)
        assertEquals(100L, entry.created)
        assertEquals(1000L, entry.retention.since)
        val restarted = TranscriptHistory(root) { now }
        val restored = restarted.list().single()
        assertEquals(1234L, restored.durationMs)
        assertTrue(restored.speakerLabels)
        now = 100L + HistoryRetention.HOUR.millis
        restarted.prune(HistoryRetention.HOUR)
        assertEquals(entry.id, restarted.list().single().id)
        now = 1000L + HistoryRetention.HOUR.millis
        restarted.prune(HistoryRetention.HOUR)
        assertTrue(restarted.list().isEmpty())
    }

    @Test fun repeatedManualSaveAndAudioDeletionDoNotReplaceTextMetadata() {
        val audio = RecordingHistory(folder.newFolder()) { 100L }
        val recording = audio.begin(HistoryRetention.HOUR)
        recording.append(ShortArray(16000)); recording.finish(false)
        val history = TranscriptHistory(folder.newFolder()) { 200L }
        val source = folder.newFile().apply { writeText("Speaker 1: words") }
        val entry = history.save(source, "Model", recording.entry.id, created = 100, durationMs = 1000, speakerLabels = true)
        audio.delete(recording.entry.id)
        val pinned = history.save(source, "Other model", pinned = true)
        assertEquals(entry.id, pinned.id)
        assertEquals(100L, pinned.created)
        assertEquals(1000L, pinned.durationMs)
        assertTrue(pinned.speakerLabels)
        assertEquals("Speaker 1: words", pinned.file.readText())
        history.setPinned(entry.id, false, HistoryRetention.HOUR, "launch")
        assertTrue(history.get(entry.id).speakerLabels)
    }

    @Test fun oldMetadataLoadsWithUnknownDurationAndNoSpeakerLabels() {
        val audioRoot = folder.newFolder()
        val audioDir = File(audioRoot, "old-audio").apply { mkdir() }
        File(audioDir, "recording.properties").writeText("started=10\nreference=20\nsamples=16000\nstatus=saved\n")
        val audio = RecordingHistory(audioRoot).list().single()
        assertFalse(audio.speakerLabels)
        assertEquals(1000L, audio.durationMs)
        val textRoot = folder.newFolder()
        val textDir = File(textRoot, "old-text").apply { mkdir() }
        File(textDir, "transcript.properties").writeText("created=10\nmodel=Old model\n")
        File(textDir, "transcript.txt").writeText("old result")
        val text = TranscriptHistory(textRoot).list().single()
        assertEquals(0L, text.durationMs)
        assertFalse(text.speakerLabels)
        assertEquals(10L, text.retention.since)
    }

    @Test fun legacySaveDefaultsRemainCompatible() {
        val history = TranscriptHistory(folder.newFolder()) { 200L }
        val entry = history.save(folder.newFile().apply { writeText("plain words") }, "Model")
        assertEquals(200L, entry.created)
        assertEquals(200L, entry.retention.since)
        assertEquals(0L, entry.durationMs)
        assertFalse(entry.speakerLabels)
    }
}
