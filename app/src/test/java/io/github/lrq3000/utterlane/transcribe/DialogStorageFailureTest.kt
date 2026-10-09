package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import android.os.Looper
import android.system.ErrnoException
import android.system.OsConstants
import androidx.lifecycle.viewModelScope
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.history.TranscriptHistory
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DialogStorageFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app = mockk<UtterlaneApp>(relaxed = true)
    private lateinit var history: RecordingHistory
    private var model: TranscriptionDialogModel? = null

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        history = RecordingHistory(temporary.newFolder("audio"))
        every { app.recordingHistory } returns history
        every { app.transcriptHistory } returns TranscriptHistory(temporary.newFolder("text"))
        every { app.settingsRepository.visualRefreshRate } returns flowOf(30)
        every { app.getString(any()) } answers { context.getString(firstArg()) }
        every { app.cacheDir } returns temporary.newFolder("cache")
        mockkConstructor(DialogAudioActions::class)
    }

    @After fun tearDown() {
        model?.viewModelScope?.cancel()
        unmockkAll()
    }

    @Test fun importExplainsNestedStorageFullInsteadOfOpaqueWrapper() {
        every { anyConstructed<DialogAudioActions>().import(any(), any()) } throws full()
        val dialog = open(DialogInput(path = "/provider/audio"))
        await { !dialog.state.value.importing }
        assertEquals(FULL_MESSAGE, dialog.state.value.message)
    }

    @Test fun exportExplainsStorageFullAndKeepsSourceAvailable() {
        val entry = history.importAudio(byteArrayOf(1, 2, 3).inputStream(), "wav", "audio/wav")
        every { anyConstructed<DialogAudioActions>().export(any(), any(), any()) } throws full()
        val dialog = open(DialogInput(audioId = entry.id))
        await { !dialog.state.value.importing }
        dialog.exportAudio(android.net.Uri.parse("content://destination/audio"), false)
        await { !dialog.state.value.saving }
        assertEquals(FULL_MESSAGE, dialog.state.value.message)
        assertArrayEquals(byteArrayOf(1, 2, 3), entry.part(0).readBytes())
        assertEquals(entry.id, dialog.state.value.audio?.id)
    }

    @Test fun unrelatedImportErrorKeepsItsDiagnosticMessage() {
        every { anyConstructed<DialogAudioActions>().import(any(), any()) } throws IOException("Permission denied")
        val dialog = open(DialogInput(path = "/provider/audio"))
        await { !dialog.state.value.importing }
        assertEquals("Permission denied", dialog.state.value.message)
    }

    private fun open(input: DialogInput) = TranscriptionDialogModel(app, input).also { model = it }
    private fun full() = IOException("Provider write failed", ErrnoException("write", OsConstants.ENOSPC))

    private fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Dialog operation did not complete")
    }

    companion object { const val FULL_MESSAGE = "Not enough storage space. Free up some space and try again." }
}
