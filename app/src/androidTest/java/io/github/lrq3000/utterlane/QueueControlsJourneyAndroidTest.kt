package io.github.lrq3000.utterlane

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.asr.TranscriptCompletion
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.WavFile
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.onboarding.OnboardingSample
import io.github.lrq3000.utterlane.transcribe.QueueRecord
import io.github.lrq3000.utterlane.transcribe.QueueStage
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A public reading goes through the real import, recognizer, service and reader. Only the document picker's return value is supplied by the test. */
@RunWith(AndroidJUnit4::class)
class QueueControlsJourneyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val queue get() = app.transcriptionQueue
    private val ui = OnboardingTestUi()

    @Test fun pauseOneFileFinishItsSiblingThenExplicitlyContinueFromHistory() = runBlocking<Unit> {
        assertEquals("Use the isolated QA app", "io.github.lrq3000.utterlane.dhome", app.packageName)
        queue.initialized()
        assertTrue("Refusing existing queue work", queue.journal.records().isEmpty())
        for (root in listOf(File(app.filesDir, "microphone-history"), File(app.noBackupFilesDir, "transcript-history"))) {
            assertTrue("Use an empty test history; never clear $root", !root.exists() || root.listFiles()?.isEmpty() == true)
        }
        val settings = app.settingsRepository
        assertFalse("No fixture may go to an archival destination", settings.archival.first().enabled)
        app.modelManager.initializeSelection()
        assertEquals(ModelCatalog.DEFAULT, app.modelManager.selected.value)
        assertTrue("Seed the catalog-verified offline Ultra Q8 model", app.modelManager.ensureVerified())
        val guide = OnboardingRepository(app)
        val oldGuide = guide.progress.first()
        val oldAudio = settings.audioHistoryEnabled.first()
        val oldText = settings.transcriptHistoryEnabled.first()
        val oldRetention = settings.transcriptHistoryRetention.first()
        val oldSize = settings.transcriptTextSize.first()
        val short = File.createTempFile("qa-controls-short-", ".wav", app.cacheDir)
        val long = File.createTempFile("qa-controls-long-", ".wav", app.cacheDir)
        app.assets.open("onboarding/alice_wonderland_excerpt.wav").use { input -> short.outputStream().use { input.copyTo(it) } }
        assertEquals(OnboardingSample.BYTES, short.length())
        val pcm = WavFile.read(short, 0, ((short.length() - 44) / 2).toInt())
        WavFile(long).use { writer -> repeat(8) { writer.append(pcm) } }
        val sourceHashes = listOf(long, short).map(::digest)
        fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                val result = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                    clipData = ClipData.newRawUri("QA selection", uri(long)).apply { addItem(ClipData.Item(uri(short))) }
                }
                return Instrumentation.ActivityResult(Activity.RESULT_OK, result)
            }
        }
        var home: Activity? = null
        instrumentation.addMonitor(picker)
        try {
            guide.complete()
            settings.setAudioHistoryEnabled(false)
            settings.setTranscriptHistoryEnabled(true)
            settings.setTranscriptHistoryRetention(HistoryRetention.FOREVER)
            ui.prepare()
            home = launchNativeActivity(HomeActivity.intent(app))
            ui.click("home_load_audio")
            ui.awaitChecked("file_setup_background", false)
            ui.click("file_setup_background")
            ui.awaitChecked("file_setup_background", true)
            ui.awaitChecked("file_setup_speakers", false)
            ui.click("file_setup_start")
            val rows = withTimeout(30_000) { queue.rows.first { it.size == 2 } }
            val first = rows.single { it.label == long.name }
            val second = rows.single { it.label == short.name }
            assertNotEquals(first.audio, second.audio)
            withTimeout(15_000) {
                var returned = false
                while (!returned) {
                    instrumentation.runOnMainSync { returned = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === home }
                    if (!returned) delay(100)
                }
            }
            ui.click("home_queue")
            ui.node("queue_job_${second.id}").recycle()
            ui.click("queue_job_${first.id}")
            awaitDetail(first)
            // Pause a real partial, not a waiting descriptor or an empty startup window.
            val target = queue.target(first.id)
            withTimeout(150_000) {
                while (!target.isFile || !target.readText().contains("rabbit", ignoreCase = true)) delay(150)
            }
            ui.click("queue_pause")
            val paused = awaitStage(first, QueueStage.PAUSED)
            assertTrue("Expected a durable mid-file checkpoint", paused.checkpointed && paused.resumeSamples in 1L until pcm.size * 8L)
            val prefix = target.readText()
            assertTrue(prefix.contains("rabbit", ignoreCase = true))
            Log.i(TAG, "Paused ${first.id}: ${paused.resumeSamples} source samples, ${prefix.length} transcript characters; sibling=${second.id}")
            ui.node("queue_continue").recycle()
            ui.screenshot("queue-controls-paused")
            ui.click("dialog_back")
            ui.click("home_nav_transcripts")

            awaitStage(second, QueueStage.READY)
            withTimeout(30_000) { queue.foreground.first { it == null } }
            val shortTarget = queue.target(second.id)
            assertTrue(shortTarget.readText().contains("rabbit", ignoreCase = true))
            assertEquals(TranscriptCompletion.COMPLETE, TranscriptSource.read(shortTarget).completion)
            assertEquals(paused, queue.journal.find(first.id))
            assertEquals(prefix, target.readText())
            Log.i(TAG, "Sibling finished and processing service detached; first job remains exactly at its manual checkpoint")
            ui.click("home_nav_record")
            ui.node("home_screen").recycle()
            assertFalse("Home should hide the monitor when only paused/ready files remain", ui.hasNode("home_queue"))
            ui.click("home_nav_transcripts")
            ui.node("queue_job_${first.id}").recycle()
            ui.screenshot("queue-controls-history")
            ui.click("queue_cancel_${first.id}")
            ui.node("queue_cancel_confirm").recycle()
            assertTrue(ui.hasVisibleText(app.getString(R.string.queue_cancel_message, first.label)))
            assertFalse("A processing copy is not saved audio that the user can delete", ui.hasNode("queue_cancel_audio"))
            ui.screenshot("queue-controls-cancel")
            ui.click("queue_cancel_dismiss")
            assertEquals(paused, queue.journal.find(first.id))
            ui.click("queue_cancel_${second.id}")
            ui.node("queue_cancel_confirm").recycle()
            assertTrue(ui.hasVisibleText(app.getString(R.string.queue_cancel_message, second.label)))
            ui.click("queue_cancel_confirm")
            withTimeout(20_000) { queue.rows.first { remaining -> remaining.none { it.id == second.id } } }
            assertNull(app.transcriptHistory.find(second.textId))
            assertFalse(shortTarget.exists())
            assertEquals("Canceling a sibling must not retire this attempt", paused, queue.journal.find(first.id))
            assertEquals(prefix, target.readText())
            assertEquals("The provider's originals are never deleted or modified", sourceHashes, listOf(long, short).map(::digest))
            ui.click("queue_job_${first.id}")
            awaitDetail(first)
            ui.click("queue_continue")
            awaitStage(first, QueueStage.PROCESSING)
            val complete = awaitStage(first, QueueStage.READY)
            assertEquals(first.audio, complete.audio)
            assertEquals(first.textId, complete.textId)
            assertEquals(TranscriptCompletion.COMPLETE, TranscriptSource.read(target).completion)
            val full = target.readText()
            assertTrue("Continue must append to the committed text", full.startsWith(prefix) && full.length > prefix.length)
            val dialog = ui.node("transcription_dialog")
            try { assertEquals(full.trim(), ui.recognizedText("transcript_chunk_0", dialog.windowId).trim()) }
            finally { dialog.recycle() }
            Log.i(TAG, "Explicit Continue completed the original job ${first.id}; reader matched ${full.length} transcript characters")
            val size = if (oldSize == 20) 18 else 20
            ui.clickDescription(app.getString(R.string.reader_font_size))
            ui.click("reader_text_size_$size")
            withTimeout(5_000) { settings.transcriptTextSize.first { it == size } }
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<TranscribeActivity>().single().recreate()
            }
            awaitDetail(first)
            ui.clickDescription(app.getString(R.string.reader_font_size))
            ui.node("reader_text_size_$size").let { option ->
                try { assertTrue("The recreated reader must expose its checked font size", option.isVisibleToUser && option.isCheckable && option.isChecked) }
                finally { option.recycle() }
            }
            ui.screenshot("queue-controls-size")
        } finally {
            instrumentation.removeMonitor(picker)
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<TranscribeActivity>().forEach { it.finish() }
                home?.finish()
            }
            settings.setAudioHistoryEnabled(oldAudio)
            settings.setTranscriptTextSize(oldSize)
            settings.setTranscriptHistoryRetention(oldRetention)
            settings.setTranscriptHistoryEnabled(oldText)
            guide.update { oldGuide }
            // Preserve service-owned results for diagnosis until the runner exports evidence and restores its initially absent QA package.
            long.delete(); short.delete()
        }
    }

    private suspend fun awaitStage(row: QueueRecord, expected: QueueStage): QueueRecord = withTimeout(240_000) {
        queue.rows.first { records ->
            val current = records.single { it.id == row.id }
            check(current.stage != QueueStage.FAILED) { "${current.label} failed: ${current.message}" }
            current.stage == expected
        }.single { it.id == row.id }
    }

    private suspend fun awaitDetail(row: QueueRecord) = withTimeout(15_000) {
        val expected = "utterlane://transcription/${row.id}"
        var uri: String? = null
        while (uri != expected) {
            instrumentation.runOnMainSync {
                uri = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<TranscribeActivity>().singleOrNull()?.intent?.dataString
            }
            if (uri != expected) delay(100)
        }
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object { const val TAG = "QueueControlsQA" }
}
