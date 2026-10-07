package io.github.lrq3000.utterlane.history

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryPinsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun audioPinSurvivesRestartAndUnpinStartsAFreshFullDuration() {
        var now = 1000L
        val root = folder.newFolder()
        val history = RecordingHistory(root) { now }
        val audio = history.begin(HistoryRetention.NONE)
        audio.append(shortArrayOf(1)); audio.finish(true)
        history.setPinned(audio.entry.id, true, HistoryRetention.HOUR, "first")
        assertFalse(history.get(audio.entry.id).temporary)
        now += HistoryRetention.MONTH.millis
        val restarted = RecordingHistory(root) { now }
        restarted.prune(HistoryRetention.HOUR)
        assertTrue(restarted.get(audio.entry.id).pinned)
        val original = restarted.get(audio.entry.id).started
        restarted.setPinned(audio.entry.id, false, HistoryRetention.HOUR, "second")
        restarted.prune(HistoryRetention.HOUR)
        assertEquals(original, restarted.get(audio.entry.id).started)
        now += HistoryRetention.HOUR.millis - 1
        restarted.prune(HistoryRetention.HOUR)
        assertTrue(audio.entry.directory.exists())
        now++
        restarted.prune(HistoryRetention.HOUR)
        assertFalse(audio.entry.directory.exists())
    }

    @Test fun immediateUnpinWaitsForAGenuinelyNewUserLaunchAndRepinCancelsIt() {
        var now = 1000L
        val history = RecordingHistory(folder.newFolder()) { now }
        val audio = history.begin(HistoryRetention.HOUR)
        audio.append(shortArrayOf(1)); audio.finish(false)
        history.setPinned(audio.entry.id, true, HistoryRetention.NONE, "a")
        history.setPinned(audio.entry.id, false, HistoryRetention.NONE, "a")
        now += HistoryRetention.MONTH.millis
        history.prune(HistoryRetention.NONE)
        history.onUserLaunch("a"); history.prune(HistoryRetention.NONE)
        assertTrue(audio.entry.directory.exists())
        history.setPinned(audio.entry.id, true, HistoryRetention.NONE, "a")
        history.onUserLaunch("b"); history.prune(HistoryRetention.NONE)
        assertTrue(audio.entry.directory.exists())
        history.setPinned(audio.entry.id, false, HistoryRetention.NONE, "b")
        history.onUserLaunch("c"); history.prune(HistoryRetention.NONE)
        assertFalse(audio.entry.directory.exists())
    }

    @Test fun distinctAttemptsKeepIndependentTextWhenAudioExpiresAndManualSavesDeduplicate() {
        var now = 1000L
        val audioHistory = RecordingHistory(folder.newFolder()) { now }
        val history = TranscriptHistory(folder.newFolder()) { now }
        val audio = audioHistory.begin(HistoryRetention.HOUR)
        audio.append(shortArrayOf(1)); audio.finish(false)
        val source = folder.newFile().apply { writeText("Speaker 1: words") }
        val first = history.save(source, "Model A", audio.entry.id, attempt = "attempt-a")
        val again = history.save(source, "Model B", audio.entry.id, attempt = "attempt-b")
        assertNotEquals(first.id, again.id)
        assertEquals(first.id, history.save(source, "Model A", audio.entry.id, pinned = true, attempt = "attempt-a").id)
        assertEquals(2, history.list().size)
        now += HistoryRetention.DAY.millis
        audioHistory.prune(HistoryRetention.HOUR)
        history.prune(HistoryRetention.DAY)
        assertFalse(audio.entry.directory.exists())
        assertEquals(listOf(first.id), history.list().map { it.id })
        assertEquals("Speaker 1: words", first.file.readText())
        history.setPinned(first.id, false, HistoryRetention.NONE, "one")
        history.prune(HistoryRetention.NONE)
        val reopened = TranscriptHistory(first.directory.parentFile) { now }
        reopened.prune(HistoryRetention.NONE)
        assertTrue(first.file.exists())
        reopened.onUserLaunch("two"); reopened.prune(HistoryRetention.NONE)
        assertFalse(first.file.exists())
    }

    @Test fun importedAudioIsOwnedOnlyAfterCompleteCopyAndStaysUntilDismissal() {
        val history = RecordingHistory(folder.newFolder())
        val bytes = byteArrayOf(1, 2, 3)
        val entry = history.importAudio(ByteArrayInputStream(bytes), "m4a", "audio/mp4", 5000)
        assertArrayEquals(bytes, entry.part(0).readBytes())
        history.completeRecovery(entry.id, HistoryRetention.NONE)
        history.prune(HistoryRetention.NONE)
        assertTrue(entry.part(0).exists())
        history.dismiss(entry.id)
        assertFalse(entry.directory.exists())
    }
}
