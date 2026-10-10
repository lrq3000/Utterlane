package io.github.lrq3000.utterlane.audio

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MicrophoneHistoryGainTest {
    @Test fun homeOwnedAudioKeepsGainAndRecoveryMetadataAcrossHistoryHandoff() {
        val root = Files.createTempDirectory("microphone-home-handoff").toFile()
        try {
            val history = RecordingHistory(root)
            val options = MicrophoneOptions.HFP.copy(gain = PcmGainMode.DB_PLUS_6)
            val saved = history.begin(HistoryRetention.NONE, automatic = true,
                keepUntilDismissed = true, microphone = options)
            val original = shortArrayOf(123, -456, 789)
            saved.append(original)
            history.setSpeakerLabels(saved.entry.id, true)
            saved.finish(false)
            assertTrue("Home retains its current source under Immediate retention", history.get(saved.entry.id).temporary)
            history.recordFailure(saved.entry.id, "Model unavailable", "MODEL")
            history.completeRecovery(saved.entry.id, HistoryRetention.FOREVER)
            assertTrue(history.retainOnExit(saved.entry.id, HistoryExitPolicy(true, HistoryRetention.FOREVER)))

            val reopened = RecordingHistory(root).also { it.initialize() }
            val entry = reopened.get(saved.entry.id)
            assertEquals(options, entry.microphone)
            assertTrue(entry.speakerLabels)
            assertTrue("Retry/exit must preserve recovered origin", entry.recovered)
            assertFalse(entry.temporary)
            assertFalse(entry.needsRecovery)
            assertArrayEquals(original, reopened.read(entry.id, 0, original.size))
        } finally { root.deleteRecursively() }
    }

    @Test fun originalPcmAndProcessingChoiceSurviveRestartForConsistentRetries() {
        val root = Files.createTempDirectory("microphone-gain").toFile()
        try {
            val history = RecordingHistory(root)
            val options = MicrophoneOptions.HFP
            val saved = history.begin(HistoryRetention.FOREVER, microphone = options)
            val original = ShortArray(1617) { (it % 200 - 100).toShort() }
            saved.append(original); saved.finish(false)
            val reopened = RecordingHistory(root).also { it.initialize() }
            val entry = reopened.get(saved.entry.id)
            assertEquals(options, entry.microphone)
            assertArrayEquals(original, reopened.read(entry.id, 0, original.size))
            val first = TranscriptionGain(options.gain)
            val processed = first.accept(original.copyOf()) + first.finish()
            val replay = TranscriptionGain(entry.microphone!!.gain)
            reopened.openReader(entry.id).use { reader ->
                val result = replay.accept(reader.read()) + replay.finish()
                assertArrayEquals(processed, result)
            }
            assertArrayEquals("Processing never replaces original PCM", original, reopened.read(entry.id, 0, original.size))
        } finally { root.deleteRecursively() }
    }

    @Test fun oldAndImportedAudioHaveNoImplicitMicrophoneEnhancement() {
        val root = Files.createTempDirectory("microphone-legacy").toFile()
        try {
            val history = RecordingHistory(root)
            val old = history.begin(HistoryRetention.FOREVER)
            old.append(shortArrayOf(123)); old.finish(false)
            assertNull(history.get(old.entry.id).microphone)
            val imported = history.importAudio(byteArrayOf(1, 2, 3).inputStream(), "wav", "audio/wav")
            assertNull(imported.microphone)
        } finally { root.deleteRecursively() }
    }
}
