package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import java.io.File
import kotlinx.coroutines.runBlocking
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
}
