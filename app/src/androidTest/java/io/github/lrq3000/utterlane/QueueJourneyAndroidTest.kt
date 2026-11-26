package io.github.lrq3000.utterlane

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.asr.TranscriptCompletion
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.WavFile
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeCapturePhase
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.onboarding.OnboardingSample
import io.github.lrq3000.utterlane.transcribe.QueueNotifications
import io.github.lrq3000.utterlane.transcribe.QueueRecord
import io.github.lrq3000.utterlane.transcribe.QueueStage
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real file imports, services, ASR, Android notifications and result readers. Only the document chooser's returned selection and the microphone's PCM are supplied by the test. */
@RunWith(AndroidJUnit4::class)
class QueueJourneyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val queue get() = app.transcriptionQueue
    private val notices get() = app.getSystemService(NotificationManager::class.java)
    private val ui = OnboardingTestUi()

    @Test fun bulkSelectionAllowsLiveDictationAndIndependentImmediateRetentionReview() = runBlocking<Unit> {
        assertEquals("Use only the isolated Home QA package", "io.github.lrq3000.utterlane.dhome", app.packageName)
        // Immediate housekeeping affects the whole app identity. Refuse existing work, even if it is temporary and invisible in normal history lists.
        for (root in listOf(File(app.filesDir, "microphone-history"), File(app.noBackupFilesDir, "transcript-history"))) {
            assertTrue("Use an empty QA history; never clear $root for this test", !root.exists() || root.listFiles()?.isEmpty() == true)
        }
        assertTrue("Refusing pending results", queue.journal.records().isEmpty())
        assertTrue("Refusing an existing Home workspace", !app.homeController.state.value.busy && app.homeController.state.value.model == null)
        val settings = app.settingsRepository
        assertFalse("Keep this run in app-private storage", settings.archival.first().enabled)
        assertEquals("Grant RECORD_AUDIO to the QA app only before running", PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertTrue("The QA app must be permitted to post the actual notifications", notices.areNotificationsEnabled())
        app.modelManager.initializeSelection()
        assertEquals(ModelCatalog.DEFAULT, app.modelManager.selected.value)
        assertTrue("Seed the catalog-verified local Ultra Q8 model", app.modelManager.ensureVerified())

        val guide = OnboardingRepository(app)
        val oldGuide = guide.progress.first()
        val oldFactory = app.microphoneSessions
        val oldAudio = settings.audioHistoryEnabled.first() to settings.audioHistoryRetention.first()
        val oldText = settings.transcriptHistoryEnabled.first() to settings.transcriptHistoryRetention.first()
        val oldSpeakers = settings.diarizationEnabled.first()
        val short = File.createTempFile("queue-short-", ".wav", app.cacheDir)
        val long = File.createTempFile("queue-long-", ".wav", app.cacheDir)
        app.assets.open("onboarding/alice_wonderland_excerpt.wav").use { input -> short.outputStream().use { input.copyTo(it) } }
        assertEquals(OnboardingSample.BYTES, short.length())
        val pcm = WavFile.read(short, 0, ((short.length() - 44) / 2).toInt())
        WavFile(long).use { writer -> repeat(12) { writer.append(pcm) } }
        // More than one default ASR window, so the open microphone has enough PCM to produce live text without relying on a final flush at Stop.
        val capture = OnboardingRecognitionAndroidTest.FixtureCapture(pcm + ShortArray(32000) + pcm + pcm + pcm)
        val nextCapture = OnboardingRecognitionAndroidTest.FixtureCapture(pcm)
        val captureCount = AtomicInteger()
        fun uri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val result = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
            clipData = ClipData.newRawUri("QA selection", uri(long)).apply { addItem(ClipData.Item(uri(short))) }
        }
        val request = AtomicReference<Intent>()
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                request.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, result)
            }
        }
        var home: Activity? = null
        var reader: Activity? = null
        instrumentation.addMonitor(picker)
        try {
            guide.complete()
            settings.setAudioHistoryEnabled(true)
            settings.setTranscriptHistoryEnabled(true)
            settings.setAudioHistoryRetention(HistoryRetention.NONE)
            settings.setTranscriptHistoryRetention(HistoryRetention.NONE)
            settings.setDiarizationEnabled(false)
            app.microphoneSessions = MicrophoneSessionFactory {
                when (captureCount.incrementAndGet()) { 1 -> capture; 2 -> nextCapture; else -> error("Unexpected microphone activation") }
            }
            ui.prepare()
            home = launchNativeActivity(HomeActivity.intent(app))
            ui.click("home_load_audio")
            ui.node("file_setup").recycle()
            assertTrue("Home must actually request multiple selection", request.get()?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true)
            ui.scrollToText(app.getString(R.string.queue_import_count, 2))
            ui.scrollToText(app.getString(R.string.queue_warn_audio))
            ui.scrollToText(app.getString(R.string.queue_warn_text))
            assertTrue("Nothing is accepted before confirmation", queue.journal.records().isEmpty())
            ui.click("file_setup_start")
            val files = withTimeout(30_000) { queue.rows.first { it.size == 2 } }
            assertEquals(listOf(long.name, short.name), files.map { it.label })
            assertEquals(2, files.map { it.audio }.distinct().size)
            val (first, second) = files
            assertTrue("Each job owns a distinct negative Android notification ID",
                files.all { it.notificationId < 0 } && first.notificationId != second.notificationId)
            reader = awaitReader(first)
            assertEquals(first.id, reader.intent.getStringExtra(QueueNotifications.JOB))
            withTimeout(30_000) { queue.rows.first { rows -> rows.any { it.id == first.id && it.stage == QueueStage.PROCESSING } } }
            withTimeout(10_000) { while (notices.activeNotifications.none { it.id == first.notificationId && it.tag == null &&
                    it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }) delay(100) }
            ui.click("dialog_background")
            withTimeout(10_000) { while (resumed() != null || instrumentation.uiAutomation.rootInActiveWindow?.packageName.let { it == null || it == app.packageName }) delay(100) }
            assertEquals(QueueStage.PROCESSING, queue.journal.find(first.id)?.stage)
            android.util.Log.i("QueueJourneyQA", "Continue in background hid the app while the first job was processing")
            app.startActivity(checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName)))
            val returned = withTimeout(15_000) { var top = resumed(); while (top == null) { delay(100); top = resumed() }; top }
            // Standard launcher re-entry may restore the top reader. Its ordinary Back returns to the Home underneath without canceling processing.
            if (returned is TranscribeActivity) ui.click("dialog_back")
            withTimeout(10_000) { while (resumed() !is HomeActivity) delay(100) }
            home = resumed()
            ui.node("home_screen").recycle()
            instrumentation.runOnMainSync { app.homeController.record() }
            assertTrue("Protected PCM did not reach the open microphone pipeline", capture.sent.await(35, TimeUnit.SECONDS))
            val preview = withTimeout(120_000) { app.homeController.state.first {
                it.capture.phase == HomeCapturePhase.RECORDING && it.result.preview.contains("rabbit", ignoreCase = true)
            } }
            val overlap = queue.rows.value.any { it.id == first.id && it.stage == QueueStage.PROCESSING }
            android.util.Log.i("QueueJourneyQA", "Recognized live speech before Stop: ${preview.result.transcriptBytes} bytes; first file still processing=$overlap")
            instrumentation.runOnMainSync { app.homeController.record() }
            withTimeout(35_000) { app.homeController.state.first { it.queuedTail && it.capture.phase == HomeCapturePhase.PROCESSING } }
            val firstLive = queue.rows.value.single { it.stage == QueueStage.DRAINING }
            instrumentation.runOnMainSync {
                assertEquals("The first take must still be draining at second admission", QueueStage.DRAINING, queue.journal.find(firstLive.id)?.stage)
                app.homeController.record()
            }
            assertTrue("Second take was not admitted", nextCapture.sent.await(35, TimeUnit.SECONDS))
            assertEquals(2, captureCount.get())
            instrumentation.runOnMainSync { app.homeController.record() }
            val ended = withTimeout(480_000) { queue.rows.first { rows -> rows.size == 4 && rows.all { it.stage.reviewable } } }
            assertTrue("Each operation must succeed, not merely leave the queue: ${ended.map { it.stage to it.message }}", ended.all { it.stage == QueueStage.READY })
            val live = ended.single { it.id !in setOf(first.id, second.id, firstLive.id) }
            assertEquals(4, ended.map { it.audio }.distinct().size)
            // The launch/cleanup boundary is meaningful: no reader is open for either file and both ordinary retention windows are already expired.
            app.historyCleanup.request(HistoryCleanupCoordinator.BOTH)
            fun body(row: QueueRecord): String {
                val file = queue.target(row.id)
                assertTrue("Missing unreviewed ${row.label}", file.isFile)
                val source = TranscriptSource.read(file)
                assertEquals(row.audio, source.audioId)
                assertEquals(TranscriptCompletion.COMPLETE, source.completion)
                return file.readText().also { assertTrue("Expected real public speech for ${row.label}", it.contains("rabbit", true)) }
            }
            val firstText = body(first)
            val secondText = body(second)
            assertTrue("Do not deliver the same attempt for both imports", firstText.length > secondText.length * 2)
            body(live)
            body(firstLive)
            assertEquals(long.length(), app.recordingHistory.get(first.audio).part(0).length())
            assertEquals(short.length(), app.recordingHistory.get(second.audio).part(0).length())
            val firstNotice = readyNotice(first)
            val secondNotice = readyNotice(second)
            assertNotEquals("PendingIntents must not overwrite siblings", firstNotice.contentIntent, secondNotice.contentIntent)
            // Open the shorter, second result first via Android's actual posted token. Confirm the exact short text, not just a common keyword.
            secondNotice.contentIntent.send()
            reader = awaitReader(second)
            assertEquals(second.id, reader.intent.getStringExtra(QueueNotifications.JOB))
            assertEquals("utterlane://transcription/${second.id}", reader.intent.dataString)
            assertEquals(secondText.trim(), visibleDialogText().trim())
            ui.click("dialog_back")
            ui.node("exit_confirmation").recycle()
            assertNotNull("Showing a loss warning is not consent", queue.journal.find(second.id))
            ui.click("exit_discard")
            withTimeout(15_000) { queue.rows.first { rows -> rows.none { it.id == second.id } } }
            withTimeout(10_000) { while (notices.activeNotifications.any { it.id == second.notificationId && it.tag == null } || queue.target(second.id).exists() ||
                    app.transcriptHistory.find(second.textId) != null || runCatching { app.recordingHistory.get(second.audio) }.isSuccess) delay(100) }
            assertEquals(firstText, body(first))
            assertTrue(app.recordingHistory.get(first.audio).part(0).isFile)
            readyNotice(first)
            // Exercise the independent in-app path for people who do not open the notification.
            ui.click("home_nav_transcripts")
            ui.click("queue_job_${first.id}")
            reader = awaitReader(first)
            assertEquals(first.id, reader.intent.getStringExtra(QueueNotifications.JOB))
            visibleDialogText()
            ui.click("dialog_back")
            ui.click("exit_discard")
            withTimeout(15_000) { queue.rows.first { rows -> rows.map { it.id }.toSet() == setOf(firstLive.id, live.id) } }
            readyNotice(firstLive).contentIntent.send()
            reader = awaitReader(firstLive)
            assertEquals(firstLive.id, reader.intent.getStringExtra(QueueNotifications.JOB))
            visibleDialogText()
            ui.click("dialog_back")
            ui.click("exit_discard")
            withTimeout(15_000) { queue.rows.first { rows -> rows.singleOrNull()?.id == live.id } }
            withTimeout(30_000) { app.homeController.state.first { !it.busy && it.model?.input?.queueId == live.id && it.result.audio != null } }
            ui.click("home_nav_record")
            instrumentation.runOnMainSync { app.homeController.dismiss() }
            ui.click("exit_discard")
            withTimeout(15_000) { queue.rows.first { it.isEmpty() } }
            assertTrue("Live words were not delivered while the long file was still processing", overlap)
        } finally {
            capture.stop()
            nextCapture.stop()
            instrumentation.runOnMainSync { reader?.finish(); home?.finish() }
            instrumentation.removeMonitor(picker)
            app.microphoneSessions = oldFactory
            settings.setDiarizationEnabled(oldSpeakers)
            settings.setAudioHistoryRetention(oldAudio.second)
            settings.setTranscriptHistoryRetention(oldText.second)
            settings.setAudioHistoryEnabled(oldAudio.first)
            settings.setTranscriptHistoryEnabled(oldText.first)
            guide.update { oldGuide }
            // Only these two private source copies belong to the harness. On a failed assertion, leave any pending jobs or results for diagnosis.
            short.delete(); long.delete()
        }
    }

    private fun resumed(): Activity? {
        var top: Activity? = null
        instrumentation.runOnMainSync { top = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() }
        return top
    }

    private suspend fun awaitReader(row: QueueRecord): TranscribeActivity = withTimeout(15_000) {
        // ActivityMonitor buffers previously resumed instances (including launcher re-entry). A stale callback is not evidence of where this particular notification or queue tap took the user.
        var activity = resumed()
        while (activity !is TranscribeActivity || activity.intent.dataString != "utterlane://transcription/${row.id}") {
            delay(100)
            activity = resumed()
        }
        activity
    }

    private fun visibleDialogText(): String {
        // Home behind a translucent reader also contains the public reading. It must not satisfy the foreground dialog's evidence.
        val dialog = ui.node("transcription_dialog")
        return try { ui.recognizedText(id = "transcript_chunk_0", windowId = dialog.windowId) }
        finally { @Suppress("DEPRECATION") dialog.recycle() }
    }

    private suspend fun readyNotice(row: QueueRecord): Notification = withTimeout(15_000) {
        var found: Notification? = null
        while (found == null) {
            found = notices.activeNotifications.singleOrNull { it.id == row.notificationId && it.tag == null }?.notification?.takeIf {
                it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == row.label &&
                    it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == app.getString(R.string.queue_ready)
            }
            if (found == null) delay(100)
        }
        found
    }
}
