package io.github.lrq3000.utterlane.service

import android.content.ClipboardManager
import android.os.Looper
import android.os.PowerManager
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.HistoryRetention
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = UtterlaneApp::class, sdk = [36])
class StreamingTextTargetTest {
    private val app = RuntimeEnvironment.getApplication() as UtterlaneApp

    @After fun stopAppJobs() { app.applicationScope.cancel() }

    @Test fun clipboardDeliveryDiscardsTemporaryAudioButKeepsTextRecovery() {
        val audio = deliverWithoutEditor("Keep these words")
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("Keep these words", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        await("Temporary audio survived successful clipboard delivery") { !audio.exists() }
    }

    @Test fun transcriptTooLargeForClipboardLeavesAudioAvailableForRecovery() {
        val audio = deliverWithoutEditor("x".repeat(TranscriptStore.TRANSFER_LIMIT + 1))
        assertFalse(app.getSystemService(ClipboardManager::class.java).hasPrimaryClip())
        assertTrue(audio.isDirectory)
        assertTrue(app.recordingHistory.list().single().needsRecovery)
    }

    @Test fun detachedTargetKeepsPrivateRecoveryWithoutWritingTheClipboard() {
        val audio = deliverWithoutEditor("Private recovered text", detached = true)
        assertFalse(app.getSystemService(ClipboardManager::class.java).hasPrimaryClip())
        assertTrue(audio.isDirectory)
    }

    @Test fun overlayCanRetireSuccessfulAudioOncePrivateTextRecoveryIsPublished() {
        val audio = deliverWithoutEditor("x".repeat(TranscriptStore.TRANSFER_LIMIT + 1), retireAfterRecovery = true)
        assertFalse(app.getSystemService(ClipboardManager::class.java).hasPrimaryClip())
        await("Successful overlay audio survived private transcript recovery") { !audio.exists() }
    }

    @Test fun emptyPreservedTranscriptDoesNotReportResultDelivery() {
        val store = TranscriptStore(File.createTempFile("empty-dictation-", ".txt", app.cacheDir))
        var completed = false
        var delivered = false
        StreamingTextTarget(app) { fail("An empty store has no text to insert"); false }
            .finish(store, preserve = true, afterDelivery = { completed = true },
                onResultDelivered = { delivered = true })
        await("Empty transcript finalization did not finish") { completed }
        assertFalse(store.file.exists())
        assertFalse("An empty transcript is not a delivered result", delivered)
    }

    @Suppress("DEPRECATION")
    private fun deliverWithoutEditor(text: String, detached: Boolean = false, retireAfterRecovery: Boolean = false): File {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val recording = app.recordingHistory.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recording.append(shortArrayOf(1, -1))
        recording.finish(false)
        assertTrue(recording.entry.directory.isDirectory)
        val store = TranscriptStore(File.createTempFile("dictation-", ".txt", app.cacheDir)).apply {
            attachSource(TranscriptSource(audioId = recording.entry.id))
            append(text)
        }
        var completed = false
        val target = StreamingTextTarget(app, available = { false }) { fail("An unavailable editor must not receive text"); false }
        if (detached) target.close()
        val retireAudio = { finishForegroundDictation(app, recording.entry.id, null) }
        target.finish(store, afterDelivery = { completed = true },
            onResultDelivered = retireAudio, onResultPreserved = retireAudio.takeIf { retireAfterRecovery })
        await("Transcript finalization did not finish") { completed }
        val recovery = app.getSharedPreferences("transcript_recovery", 0)
        await("Text recovery was not published") { recovery.getString("path", null) != null }
        assertEquals(text, File(recovery.getString("path", null)!!).readText())
        return recording.entry.directory
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail(message)
    }
}
