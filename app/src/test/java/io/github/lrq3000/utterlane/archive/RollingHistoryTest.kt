package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollingHistoryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun badOptionalCodecMetadataCannotStrandTheCaptureOwnerAtFinish() {
        val history = RecordingHistory(temp.newFolder())
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        recording.append(ShortArray(3200) { 42 }); recording.closeCapture()
        java.io.File(recording.entry.directory, "encoded.properties").writeText("frames=invalid\nbytes=invalid\n")
        recording.finish(false)
        assertEquals(3200L, history.get(recording.entry.id).samples)
        assertFalse("Capture lease must be released before deletion", history.hasLeases(recording.entry.id))
        history.delete(recording.entry.id)
        val remains = recording.entry.directory.exists()
        assertFalse(if (remains) "Undeleted ${recording.entry.directory}: ${recording.entry.directory.list()?.toList()}" else "", remains)
    }
    @Test fun nonNativeWavDoesNotKeepASecondFullNormalizedRecording() {
        val history = RecordingHistory(temp.newFolder())
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(true, SavedAudioFormat.WAV, channels = 2))
        recording.append(ShortArray(6400) { 42 }); recording.finish(false)
        val normalized = java.io.File(recording.entry.directory, "audio-0.wav")
        assertTrue(normalized.exists())
        while (history.cleanupPublishedEncoding()) { }
        assertFalse(normalized.exists())
        assertTrue(recording.rolling!!.pcm(0).exists())
        assertEquals(2, history.get(recording.entry.id).asset(0).channels)
    }
    @Test fun readerKeepsAbsoluteNativeOffsetsAfterOldPartsWereRemoved() {
        val history = RecordingHistory(temp.newFolder())
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        val input = ShortArray(3200) { (it * 19).toShort() }
        repeat(310) { recording.append(input) }
        val spool = checkNotNull(recording.rolling)
        spool.commitAsr(992000, 16000)
        spool.checkpointEncoded(960000, 150)
        java.io.File(spool.directory, "audio.frames").writeBytes(ByteArray(150))
        assertEquals(2, spool.reclaim())
        history.openReader(recording.entry.id, 960000).use { reader -> assertArrayEquals(input, reader.read()) }
        recording.finish(false)
        assertEquals(62000L, history.get(recording.entry.id).durationMs)
    }
    @Test fun stereoArchiveKeepsRealSamplesAndAsrUsesNormalizedMono() {
        val history = RecordingHistory(temp.newFolder())
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(true, channels = 2))
        val raw = ShortArray(6400) { if (it % 2 == 0) 1000 else 3000 }
        recording.append(raw)
        assertArrayEquals(raw, recording.rolling!!.readFrames(0, 3200))
        history.openReader(recording.entry.id).use { assertArrayEquals(ShortArray(3200) { 2000 }, it.read()) }
        recording.finish(false)
    }
}
