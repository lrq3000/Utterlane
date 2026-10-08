package io.github.lrq3000.utterlane.history

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptMetadataTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun microphoneSnapshotUsesWrittenSamplesBeforeAudioFinalization() {
        val audio = RecordingHistory(folder.newFolder()) { 100L }
        val recording = audio.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recording.append(ShortArray(24000))
        val metadata = TranscriptMetadata(recording.entry, durationMs = recording.writtenSamples * 1000 / 16000,
            speakerLabels = true)
        assertEquals(0L, recording.entry.durationMs)
        val saved = metadata.save(TranscriptHistory(folder.newFolder()) { 9000L },
            folder.newFile().apply { writeText("Speaker 1: words") }, "Model", recording.entry.id)
        recording.finish(false)
        assertEquals(100L, saved.created)
        assertEquals(1500L, saved.durationMs)
        assertTrue(saved.speakerLabels)
        assertEquals(9000L, saved.retention.since)
    }

    @Test fun manualSaveRetainsSourceMetadataAfterSourceDeletion() {
        val audio = RecordingHistory(folder.newFolder()) { 200L }
        val source = audio.importAudio(ByteArrayInputStream(byteArrayOf(1)), "mp3", "audio/mpeg", 3456)
        audio.setSpeakerLabels(source.id, true)
        // A new plain-text attempt must describe its own output, not the last
        // labeled attempt's audio badge or a setting that changed after decoding.
        val metadata = TranscriptMetadata(audio.get(source.id), speakerLabels = false)
        audio.delete(source.id)
        val saved = metadata.save(TranscriptHistory(folder.newFolder()) { 800L },
            folder.newFile().apply { writeText("plain words") }, "Model", source.id, pinned = true)
        assertEquals(200L, saved.created)
        assertEquals(3456L, saved.durationMs)
        assertFalse(saved.speakerLabels)
        assertTrue(saved.retention.pinned)
    }

    @Test fun savedTextSnapshotKeepsMetadataWithoutAnAudioLookup() {
        val history = TranscriptHistory(folder.newFolder()) { 900L }
        val source = folder.newFile().apply { writeText("Speaker 1: words") }
        val entry = history.save(source, "Model", "missing-audio", created = 100, durationMs = 2345, speakerLabels = true)
        val metadata = TranscriptMetadata(entry)
        val copy = folder.newFile().apply { writeText(source.readText()) }
        val saved = metadata.save(history, copy, "Model", entry.audioId, pinned = true)
        assertEquals(entry.created, saved.created)
        assertEquals(entry.durationMs, saved.durationMs)
        assertEquals(entry.speakerLabels, saved.speakerLabels)
    }
}
