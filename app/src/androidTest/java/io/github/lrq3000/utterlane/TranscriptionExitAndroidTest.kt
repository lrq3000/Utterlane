package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogModel
import io.github.lrq3000.utterlane.transcribe.DialogInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionExitAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    private inner class Fixture(val recovered: Boolean = true) : AutoCloseable {
        val audio = app.recordingHistory.begin(HistoryRetention.NONE, keepUntilDismissed = true).also {
            it.append(ShortArray(3200)); it.finish(recovered)
        }.entry
        val text = TranscriptStore(File.createTempFile("exit-recovery-", ".txt",
            File(app.cacheDir, "transcripts").apply { mkdirs() })).also {
            it.attachSource(TranscriptSource(audio.id, modelName = "Recovery test"))
            it.append("Words recovered after interruption")
            it.keepForRecovery()
        }
        var screen: Activity? = null
        fun open() {
            screen = instrumentation.startActivitySync(Intent(app, TranscribeActivity::class.java)
                .putExtra("transcript_path", text.file.absolutePath)
                .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui.textNode("Words recovered after interruption").recycle()
        }
        suspend fun withModel(action: suspend (TranscriptionDialogModel) -> Unit) {
            val owners = ViewModelStore()
            lateinit var model: TranscriptionDialogModel
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, DialogInput(transcriptPath = text.file.absolutePath, recovered = recovered))
                owners.put("dialog", model)
            }
            try {
                withTimeout(5000) { model.state.first { !it.importing } }
                action(model)
            } finally { instrumentation.runOnMainSync { owners.clear() } }
        }
        override fun close() {
            screen?.let { instrumentation.runOnMainSync { it.finish() } }
            app.transcriptHistory.forAudio(audio.id).forEach { app.transcriptHistory.delete(it.id) }
            app.recordingHistory.delete(audio.id)
            TranscriptStore.deleteArtifacts(text.file)
        }
    }

    private suspend fun withPolicy(enabled: Boolean, retention: HistoryRetention, action: suspend () -> Unit) {
        val settings = app.settingsRepository
        val old = listOf(settings.audioHistoryEnabled.first(), settings.transcriptHistoryEnabled.first())
        val audio = settings.audioHistoryRetention.first()
        val text = settings.transcriptHistoryRetention.first()
        try {
            settings.setAudioHistoryEnabled(enabled); settings.setTranscriptHistoryEnabled(enabled)
            settings.setAudioHistoryRetention(retention); settings.setTranscriptHistoryRetention(retention)
            action()
        } finally {
            settings.setAudioHistoryEnabled(old[0]); settings.setTranscriptHistoryEnabled(old[1])
            settings.setAudioHistoryRetention(audio); settings.setTranscriptHistoryRetention(text)
        }
    }

    @Test fun backRetainsRecoveredAudioAndTextUnderHistoryPolicy() {
        ui.prepare()
        runBlocking {
        withPolicy(true, HistoryRetention.HOUR) {
            Fixture().use { fixture ->
                fixture.open()
                ui.click("dialog_back")
                withTimeout(5000) { while (fixture.screen?.isFinishing != true) delay(50) }
                assertTrue("Ordinary Back must retain recovered audio", fixture.audio.directory.exists())
                assertEquals("Recovered working text must enter transcript history", 1,
                    app.transcriptHistory.forAudio(fixture.audio.id).size)
            }
        }
        }
    }

    @Test fun ordinaryClosePublishesRecoveredContentBeforeReleasingWorkingCopies() = runBlocking {
        withPolicy(true, HistoryRetention.HOUR) {
            Fixture().use { fixture ->
                fixture.withModel { model ->
                    instrumentation.runOnMainSync { model.requestExit() }
                    withTimeout(5000) { model.state.first { it.exited } }
                    assertTrue("Ordinary close must retain recovered audio", fixture.audio.directory.exists())
                    assertEquals(1, app.transcriptHistory.forAudio(fixture.audio.id).size)
                    assertTrue(app.transcriptHistory.forAudio(fixture.audio.id).single().recovered)
                    assertTrue(app.recordingHistory.get(fixture.audio.id).recovered)
                }
            }
        }
    }

    @Test fun disabledHistoryOffersGoBackThenPinPreservesBoth() = runBlocking {
        withPolicy(false, HistoryRetention.HOUR) {
            Fixture().use { fixture -> fixture.withModel { model ->
                instrumentation.runOnMainSync { model.requestExit() }
                val question = withTimeout(5000) { model.state.first { it.exitRequest != null } }.exitRequest!!
                assertNotNull(question.audioId); assertNotNull(question.transcriptId)
                instrumentation.runOnMainSync { model.cancelExit() }
                assertNull(model.state.value.exitRequest)
                assertFalse(model.state.value.exited)
                assertTrue(fixture.audio.part(0).exists()); assertTrue(fixture.text.file.exists())
                instrumentation.runOnMainSync { model.requestExit() }
                withTimeout(5000) { model.state.first { it.exitRequest != null } }
                instrumentation.runOnMainSync { model.confirmExit(pin = true) }
                withTimeout(5000) { model.state.first { it.exited } }
                assertTrue(app.recordingHistory.get(fixture.audio.id).pinned)
                assertTrue(app.transcriptHistory.forAudio(fixture.audio.id).single().retention.pinned)
            } }
        }
    }

    @Test fun immediateDiscardRemovesOnlyAtRiskDataAndPreservesPinnedSibling() = runBlocking {
        withPolicy(true, HistoryRetention.NONE) {
            Fixture().use { fixture ->
                val sibling = app.transcriptHistory.save(fixture.text.file, "Older version", fixture.audio.id,
                    pinned = true, attempt = "sibling-${fixture.audio.id}")
                fixture.withModel { model ->
                    instrumentation.runOnMainSync { model.requestExit() }
                    withTimeout(5000) { model.state.first { it.exitRequest != null } }
                    instrumentation.runOnMainSync { model.confirmExit(pin = false) }
                    withTimeout(5000) { model.state.first { it.exited } }
                    assertFalse(fixture.audio.directory.exists())
                    assertEquals(listOf(sibling.id), app.transcriptHistory.forAudio(fixture.audio.id).map { it.id })
                }
            }
        }
    }

    @Test fun failedPinKeepsDialogOpenAndCanBeRetried() = runBlocking {
        withPolicy(false, HistoryRetention.NONE) {
            Fixture().use { fixture -> fixture.withModel { model ->
                instrumentation.runOnMainSync { model.requestExit() }
                withTimeout(5000) { model.state.first { it.exitRequest != null } }
                // Real filesystem failure at the existing atomic metadata write,
                // without introducing a production-only mock or timeout path.
                val obstruction = File(fixture.audio.directory, "recording.properties.tmp")
                assertTrue(obstruction.mkdir())
                try {
                    instrumentation.runOnMainSync { model.confirmExit(pin = true) }
                    val failed = withTimeout(5000) { model.state.first { !it.closing && it.message != null } }
                    assertFalse(failed.exited); assertNotNull(failed.exitRequest)
                    assertTrue(fixture.text.file.exists()); assertTrue(fixture.audio.part(0).exists())
                } finally { obstruction.delete() }
                instrumentation.runOnMainSync { model.confirmExit(pin = true) }
                withTimeout(5000) { model.state.first { it.exited } }
                assertTrue(app.recordingHistory.get(fixture.audio.id).pinned)
                assertTrue(app.transcriptHistory.forAudio(fixture.audio.id).single().retention.pinned)
            } }
        }
    }

    @Test fun newlyExpiredKindRequiresRenewedDiscardConsent() = runBlocking {
        withPolicy(true, HistoryRetention.HOUR) {
            Fixture().use { fixture -> fixture.withModel { model ->
                app.settingsRepository.setTranscriptHistoryEnabled(false)
                instrumentation.runOnMainSync { model.requestExit() }
                val initial = withTimeout(5000) { model.state.first { it.exitRequest != null } }.exitRequest!!
                assertNull(initial.audioId); assertNotNull(initial.transcriptId)
                app.settingsRepository.setAudioHistoryRetention(HistoryRetention.NONE)
                instrumentation.runOnMainSync { model.confirmExit(pin = false) }
                val renewed = withTimeout(5000) { model.state.first { !it.closing && it.exitRequest?.audioId != null } }
                assertFalse(renewed.exited)
                assertTrue(fixture.audio.part(0).exists()); assertTrue(fixture.text.file.exists())
                instrumentation.runOnMainSync { model.confirmExit(pin = true) }
                withTimeout(5000) { model.state.first { it.exited } }
            } }
        }
    }

    @Test fun androidBackAndToolbarBackOfferTheSameChoices() {
        ui.prepare()
        runBlocking {
            withPolicy(false, HistoryRetention.NONE) {
                Fixture().use { fixture ->
                    fixture.open()
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    ui.node("exit_confirmation").recycle()
                    ui.screenshot("polish-exit-loss-warning")
                    ui.click("exit_back")
                    assertFalse(fixture.screen!!.isFinishing)
                    ui.click("dialog_back")
                    ui.node("exit_confirmation").recycle()
                    ui.click("exit_pin")
                    withTimeout(5000) { while (fixture.screen?.isFinishing != true) delay(50) }
                    assertTrue(app.recordingHistory.get(fixture.audio.id).pinned)
                    assertTrue(app.transcriptHistory.forAudio(fixture.audio.id).single().retention.pinned)
                }
            }
        }
    }

    @Test fun completedActionsAndExistingFailurePathsProduceToastFeedback() {
        ui.prepare()
        fun toast(expected: String, action: () -> Unit) {
            val event = instrumentation.uiAutomation.executeAndWaitForEvent(action,
                { it.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    it.text.any { text -> text.toString().contains(expected) } }, 10000)
            @Suppress("DEPRECATION") event.recycle()
        }
        runBlocking {
            withPolicy(true, HistoryRetention.HOUR) {
                Fixture().use { fixture ->
                    fixture.open()
                    ui.click("dialog_pin")
                    toast(app.getString(R.string.action_feedback_pinned_both)) { ui.click("pin_choice_both") }
                    toast(app.getString(R.string.action_feedback_copied)) { ui.click("dialog_copy") }
                    ui.click("dialog_audio_menu")
                    toast(app.getString(R.string.action_feedback_pinned_audio)) { ui.clickText(app.getString(R.string.dialog_save_history)) }
                    toast(app.getString(R.string.action_feedback_share)) { ui.click("dialog_share") }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    ui.node("dialog_audio_menu").recycle()
                    ui.click("dialog_audio_menu")
                    toast(app.getString(R.string.action_feedback_share)) { ui.clickText(app.getString(R.string.dialog_share_audio)) }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    lateinit var model: TranscriptionDialogModel
                    instrumentation.runOnMainSync {
                        model = ViewModelProvider(fixture.screen as ViewModelStoreOwner)[TranscriptionDialogModel::class.java]
                    }
                    val destination = File.createTempFile("export-feedback-", ".wav", app.cacheDir)
                    try {
                        toast(app.getString(R.string.action_feedback_audio_saved)) {
                            instrumentation.runOnMainSync { model.exportAudio(android.net.Uri.fromFile(destination), directory = false) }
                        }
                        assertTrue(destination.length() > 44)
                        // A file URI with a missing parent fails in the existing IO
                        // catch path. No special failure-detection mechanism exists.
                        val invalid = File(app.cacheDir, "absent-${fixture.audio.id}/audio.wav")
                        toast("ENOENT") {
                            instrumentation.runOnMainSync { model.exportAudio(android.net.Uri.fromFile(invalid), directory = false) }
                        }
                        assertFalse(model.state.value.exited)
                    } finally { destination.delete() }
                }
            }
        }
    }

    @Test fun ordinaryTemporaryResultsAlsoWarnWithoutBeingMarkedRecovered() = runBlocking {
        withPolicy(false, HistoryRetention.NONE) {
            Fixture(recovered = false).use { fixture -> fixture.withModel { model ->
                instrumentation.runOnMainSync { model.requestExit() }
                val question = withTimeout(5000) { model.state.first { it.exitRequest != null } }.exitRequest!!
                assertNotNull(question.audioId); assertNotNull(question.transcriptId)
                instrumentation.runOnMainSync { model.confirmExit(pin = true) }
                withTimeout(5000) { model.state.first { it.exited } }
                assertFalse(app.recordingHistory.get(fixture.audio.id).recovered)
                assertFalse(app.transcriptHistory.forAudio(fixture.audio.id).single().recovered)
            } }
        }
    }

    @Test fun textOnlyRecoveryCanBePinnedAfterItsSourceAudioDisappears() = runBlocking {
        withPolicy(false, HistoryRetention.NONE) {
            Fixture().use { fixture ->
                app.recordingHistory.delete(fixture.audio.id)
                fixture.withModel { model ->
                    instrumentation.runOnMainSync { model.requestExit() }
                    val question = withTimeout(5000) { model.state.first { it.exitRequest != null } }.exitRequest!!
                    assertNull(question.audioId); assertNotNull(question.transcriptId)
                    instrumentation.runOnMainSync { model.confirmExit(pin = true) }
                    withTimeout(5000) { model.state.first { it.exited } }
                    val saved = app.transcriptHistory.forAudio(fixture.audio.id).single()
                    assertTrue(saved.retention.pinned); assertTrue(saved.recovered)
                }
            }
        }
    }

    @Test fun savedEntriesRemainPinnableWhenRetentionExpiresWhileViewing() = runBlocking {
        withPolicy(true, HistoryRetention.HOUR) {
            Fixture().use { fixture ->
                app.recordingHistory.setPinned(fixture.audio.id, false, HistoryRetention.HOUR, app.historyCleanup.launchToken)
                val saved = app.transcriptHistory.save(fixture.text.file, "Saved model", fixture.audio.id)
                TranscriptSource(fixture.audio.id, saved.id).write(fixture.text.file)
                fixture.withModel { model ->
                    app.settingsRepository.setAudioHistoryRetention(HistoryRetention.NONE)
                    app.settingsRepository.setTranscriptHistoryRetention(HistoryRetention.NONE)
                    app.historyCleanup.request(HistoryCleanupCoordinator.BOTH)
                    assertNotNull(app.transcriptHistory.find(saved.id))
                    instrumentation.runOnMainSync { model.requestExit() }
                    val question = withTimeout(5000) { model.state.first { it.exitRequest != null } }.exitRequest!!
                    assertNotNull(question.audioId); assertEquals(saved.id, question.transcriptId)
                    instrumentation.runOnMainSync { model.confirmExit(pin = true) }
                    withTimeout(5000) { model.state.first { it.exited } }
                    assertTrue(app.recordingHistory.get(fixture.audio.id).pinned)
                    assertTrue(app.transcriptHistory.get(saved.id).retention.pinned)
                }
            }
        }
    }
}
