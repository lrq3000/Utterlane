package io.github.lrq3000.utterlane.history

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MicrophoneRecordingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun disabledHistoryUsesSeparateTemporaryFilesAndDeletesOnClose() {
        val history = RecordingHistory(File(temporary.root, "history"))
        val repository = MicrophoneRecordings(history, File(temporary.root, "temporary"))
        val audio = repository.begin(HistoryRetention.NONE)
        audio.append(shortArrayOf(1, 2, 3)); audio.finish(false)
        assertTrue(audio.temporary)
        assertTrue(history.list().isEmpty())
        assertArrayEquals(shortArrayOf(1, 2, 3), audio.read(0, 3))
        val directory = audio.entry.directory
        audio.close()
        assertFalse(directory.exists())
    }

    @Test fun recoveryOwnsTemporaryAudioAcrossReadersUntilExplicitDiscard() {
        val repository = MicrophoneRecordings(RecordingHistory(File(temporary.root, "history")), File(temporary.root, "temporary"))
        val audio = repository.begin(HistoryRetention.NONE)
        audio.append(shortArrayOf(7, 8)); audio.finish(true)
        repository.recover(audio)
        assertSame(audio, repository.get(audio.entry.id))
        val reader = audio.acquire()
        repository.discard(audio.entry.id)
        assertTrue("An active retry must keep its audio", audio.entry.directory.exists())
        assertArrayEquals(shortArrayOf(7, 8), audio.read(0, 2))
        reader.close()
        assertFalse(audio.entry.directory.exists())
        assertTrue(repository.pending.value.isEmpty())
    }

    @Test fun retainedAudioObeysHistoryRetentionInsteadOfRecoveryLifetime() {
        val history = RecordingHistory(File(temporary.root, "history"))
        val repository = MicrophoneRecordings(history, File(temporary.root, "temporary"))
        val audio = repository.begin(HistoryRetention.FOREVER)
        audio.append(shortArrayOf(9)); audio.finish(true)
        repository.recover(audio)
        history.delete(audio.entry.id)
        assertFalse("Pending recovery must not override explicit history deletion", audio.entry.directory.exists())
        repository.discard(audio.entry.id)
    }

    @Test fun startupCleanupRemovesAbandonedAudioButCannotDeleteANewActiveRecording() {
        val root = File(temporary.root, "temporary").apply { mkdirs() }
        File(root, "abandoned.wav").writeText("old process")
        val repository = MicrophoneRecordings(RecordingHistory(File(temporary.root, "history")), root)
        repository.initializeTemporaryStorage()
        assertFalse(File(root, "abandoned.wav").exists())
        val audio = repository.begin(HistoryRetention.NONE)
        audio.append(shortArrayOf(3))
        repository.initializeTemporaryStorage()
        assertArrayEquals(shortArrayOf(3), audio.read(0, 1))
        audio.finish(false); audio.close()
    }
}
