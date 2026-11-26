package io.github.lrq3000.utterlane

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.asr.TranscriptCompletion
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.WavFile
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeCapturePhase
import io.github.lrq3000.utterlane.home.HomeDestination
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.onboarding.OnboardingSample
import io.github.lrq3000.utterlane.transcribe.QueueJournal
import io.github.lrq3000.utterlane.transcribe.QueueRecord
import io.github.lrq3000.utterlane.transcribe.QueueStage
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run each journey on an initially empty isolated QA identity. The app's real importer, recognizer, services and readers process bundled speech; the test supplies the picker response and, for the live-take case, the microphone PCM. Leave each journey's results available for evidence export before restoring the fixture. */
@RunWith(AndroidJUnit4::class)
class QueueControlsJourneyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val queue get() = app.transcriptionQueue
    private val ui = OnboardingTestUi()

    private suspend fun requireIsolatedWorkspace() {
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
    }

    @Test fun pauseOneFileFinishItsSiblingThenExplicitlyContinueFromHistory() = runBlocking<Unit> {
        requireIsolatedWorkspace()
        val settings = app.settingsRepository
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

    @Test fun liveCompletionLeavesFilePausedAndRestartCreatesOneStoppableAttempt() = runBlocking<Unit> {
        requireIsolatedWorkspace()
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertTrue("Refuse an existing live workspace", !app.homeController.state.value.busy && app.homeController.state.value.model == null)
        val settings = app.settingsRepository
        val oldFactory = app.microphoneSessions
        val oldSpeaker = settings.diarizationEnabled.first()
        val guide = OnboardingRepository(app)
        val oldGuide = guide.progress.first()
        val short = File.createTempFile("qa-stop-restart-voice-", ".wav", app.cacheDir)
        val long = File.createTempFile("qa-stop-restart-file-", ".wav", app.cacheDir)
        app.assets.open("onboarding/alice_wonderland_excerpt.wav").use { input -> short.outputStream().use { input.copyTo(it) } }
        assertEquals(OnboardingSample.BYTES, short.length())
        val pcm = WavFile.read(short, 0, ((short.length() - 44) / 2).toInt())
        WavFile(long).use { writer -> repeat(8) { writer.append(pcm) } }
        val original = digest(long)
        val capture = OnboardingRecognitionAndroidTest.FixtureCapture(pcm + ShortArray(32_000) + pcm + pcm)
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", long)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        var home: Activity? = null
        instrumentation.addMonitor(picker)
        try {
            guide.complete()
            settings.setDiarizationEnabled(false)
            app.microphoneSessions = MicrophoneSessionFactory { capture }
            ui.prepare()
            home = launchNativeActivity(HomeActivity.intent(app))
            ui.click("home_load_audio")
            ui.click("file_setup_start")
            val file = withTimeout(30_000) { queue.rows.first { it.size == 1 }.single() }
            assertEquals(long.name, file.label)
            awaitDetail(file)
            awaitWords(file)
            ui.click("queue_pause")
            val paused = awaitStage(file, QueueStage.PAUSED)
            val partial = queue.target(file.id).readText()
            assertTrue(paused.checkpointed && paused.resumeSamples in 1L until 8L * pcm.size)
            ui.node("queue_continue").recycle()
            ui.click("dialog_back")
            ui.node("home_screen").recycle()
            instrumentation.runOnMainSync { app.homeController.record() }
            assertTrue("Bundled speech did not enter the live pipeline", capture.sent.await(35, TimeUnit.SECONDS))
            withTimeout(120_000) { app.homeController.state.first { it.capture.phase == HomeCapturePhase.RECORDING && it.result.preview.contains("rabbit", true) } }
            assertEquals(paused, queue.journal.find(file.id))
            instrumentation.runOnMainSync { app.homeController.record() }
            val live = withTimeout(120_000) { queue.rows.first { rows -> rows.any { it.id != file.id && it.stage.reviewable } }.single { it.id != file.id } }
            assertEquals("Live take failed: ${live.message}", QueueStage.READY, live.stage)
            assertNotEquals(file.audio, live.audio)
            val liveBody = queue.target(live.id).readText()
            assertTrue(liveBody.contains("rabbit", true))
            withTimeout(30_000) { queue.foreground.first { it == null } }
            // Reload only the persisted descriptors, independently of the process's hot StateFlow.
            fun durable() = QueueJournal(File(app.noBackupFilesDir, "transcription-queue")).records()
            assertEquals(paused, durable().single { it.id == file.id })
            assertEquals(partial, queue.target(file.id).readText())
            Log.i(TAG, "Live ${live.id} completed (${liveBody.length} chars); lone file ${file.id} stayed durably PAUSED at ${paused.resumeSamples}, ${partial.length} chars")

            ui.click("home_nav_transcripts")
            ui.click("queue_job_${file.id}")
            awaitDetail(file)
            ui.click("queue_restart")
            val retry = withTimeout(20_000) { queue.rows.first { it.size >= 3 }.single { it.id != file.id && it.id != live.id } }
            assertEquals(file.audio, retry.audio)
            assertNotEquals(file.textId, retry.textId)
            assertEquals(0L, retry.resumeSamples)
            assertFalse(retry.checkpointed)
            assertEquals(QueueStage.STOPPED, queue.journal.find(file.id)?.stage)
            assertEquals(partial, queue.target(file.id).readText())
            awaitDetail(retry)
            awaitStage(retry, QueueStage.PROCESSING)
            val producer = withTimeout(15_000) { queue.active.first { it?.first == retry.id } }!!.second
            val start = withTimeout(15_000) { producer.state.first { it.fileProgress != null } }.fileProgress!!
            assertTrue("Restart must begin before the old checkpoint", start.processedSamples < paused.resumeSamples)
            val beforeStop = awaitWords(retry)
            ui.click("queue_stop")
            awaitStage(retry, QueueStage.STOPPED)
            withTimeout(30_000) { queue.foreground.first { it == null } }
            val stopped = queue.target(retry.id).readText()
            assertTrue(stopped.startsWith(beforeStop))
            assertEquals(TranscriptCompletion.INCOMPLETE, TranscriptSource.read(queue.target(retry.id)).completion)
            ui.node("queue_restart").recycle()
            ui.node("queue_continue").recycle()
            assertEquals(stopped.trim(), readerWords().trim())
            ui.screenshot("queue-controls-restarted-stopped")
            // Treat navigation as navigation rather than acknowledging either attempt. Both partial readers must remain independently addressable after the producer exits.
            app.startActivity(HomeActivity.intent(app, HomeDestination.TRANSCRIPTS))
            ui.node("queue_job_${retry.id}").recycle()
            ui.click("queue_job_${file.id}")
            awaitDetail(file)
            assertEquals(partial.trim(), readerWords().trim())
            assertEquals(TranscriptCompletion.INCOMPLETE, TranscriptSource.read(queue.target(file.id)).completion)
            val saved = durable().associate { it.id to it.stage }
            assertEquals(mapOf(file.id to QueueStage.STOPPED, retry.id to QueueStage.STOPPED, live.id to QueueStage.READY), saved)
            assertEquals(liveBody, queue.target(live.id).readText())
            assertEquals(original, digest(long))
            Log.i(TAG, "Restart ${file.id} -> ${retry.id}, same audio ${file.audio}; fresh processed=${start.processedSamples}, stopped partial=${stopped.length} chars. Two independently readable STOPPED attempts and one READY live result; original SHA-256 unchanged")

            ui.click("dialog_back")
            ui.click("queue_job_${retry.id}")
            awaitDetail(retry)
            ui.click("queue_continue")
            awaitStage(retry, QueueStage.READY)
            val continued = queue.target(retry.id).readText()
            assertTrue("Continue must append to the same stopped attempt", continued.length > stopped.length && continued.startsWith(stopped))
            assertEquals(partial, queue.target(file.id).readText())
            assertEquals(original, digest(long))
        } finally {
            capture.stop()
            instrumentation.removeMonitor(picker)
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<TranscribeActivity>().forEach { it.finish() }
                home?.finish()
            }
            app.microphoneSessions = oldFactory
            settings.setDiarizationEnabled(oldSpeaker)
            guide.update { oldGuide }
            short.delete(); long.delete()
        }
    }

    private fun readerWords(): String {
        val dialog = ui.node("transcription_dialog")
        return try { ui.recognizedText("transcript_chunk_0", dialog.windowId) } finally { dialog.recycle() }
    }

    private suspend fun awaitWords(row: QueueRecord): String = withTimeout(150_000) {
        val target = queue.target(row.id)
        var text = ""
        while (!text.contains("rabbit", true)) {
            if (target.isFile) text = target.readText()
            if (!text.contains("rabbit", true)) delay(125)
        }
        text
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
