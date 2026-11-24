package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogModel
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptCompletionAndroidTest {
    @Test fun reopenSurvivingWordsShowsPartialWithoutChangingTranscriptText() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val ui = OnboardingTestUi()
        ui.prepare()
        val folder = File(app.cacheDir, "transcripts").apply { mkdirs() }
        val text = TranscriptStore(File.createTempFile("interrupted-", ".txt", folder))
        var screen: Activity? = null
        try {
            text.beginTranscription(TranscriptSource(modelName = "Fixture"))
            text.append("Words saved before interruption")
            text.finishProducing()
            text.keepForRecovery()
            screen = launchNativeActivity(Intent(app, TranscribeActivity::class.java)
                .putExtra("transcript_path", text.file.absolutePath))
            ui.node("transcript_partial").recycle()
            assertEquals("Words saved before interruption", text.file.readText())
        } finally {
            instrumentation.runOnMainSync { screen?.finish() }
            text.dispose()
        }
    }

    @Test fun audioHistoryCannotTakeTheWorkingFileOfAnActiveRetry() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val folder = File(app.cacheDir, "transcripts").apply { mkdirs() }
        val oldText = File.createTempFile("prior-attempt-", ".txt", folder).apply { writeText("Previous saved text") }
        val audio = app.recordingHistory.importAudio(byteArrayOf(7).inputStream(), "wav", "audio/wav", 500)
        val saved = app.transcriptHistory.save(oldText, "Fixture", audio.id)
        val working = TranscriptStore(File.createTempFile("active-retry-", ".txt", folder))
        val attempt = app.recordingHistory.beginProcessing(audio.id)
        var screen: Activity? = null
        try {
            working.beginTranscription(TranscriptSource(audioId = audio.id, modelName = "Fixture"))
            working.append("Live retry prefix")
            screen = launchNativeActivity(Intent(app, TranscribeActivity::class.java).putExtra(TranscribeActivity.EXTRA_AUDIO_ID, audio.id))
            lateinit var viewer: TranscriptionDialogModel
            instrumentation.runOnMainSync {
                viewer = ViewModelProvider(screen as ViewModelStoreOwner)[TranscriptionDialogModel::class.java]
            }
            val shown = withTimeout(10000) { viewer.state.first { !it.importing && it.store != null } }
            assertEquals("Previous saved text", shown.preview)
            assertEquals(saved.id, shown.transcriptId)
            instrumentation.runOnMainSync { screen?.finish() }
            working.append("and the end")
            working.markComplete()
            assertEquals("Live retry prefix and the end", working.file.readText())
        } finally {
            instrumentation.runOnMainSync { screen?.finish() }
            attempt.close()
            working.dispose()
            app.transcriptHistory.delete(saved.id)
            app.recordingHistory.delete(audio.id)
            oldText.delete()
        }
    }
}
