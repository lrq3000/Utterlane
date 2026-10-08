package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.asr.TranscriptDocument
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionDialogDAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    private inner class Fixture : AutoCloseable {
        val recording = app.recordingHistory.begin(HistoryRetention.DAY).also {
            it.append(ShortArray(3200)); it.finish(false)
            app.recordingHistory.setPinned(it.entry.id, true, HistoryRetention.DAY, app.historyCleanup.launchToken)
        }.entry
        private val source = File.createTempFile("dialog-d-", ".txt", app.cacheDir)
        val first = app.transcriptHistory.save(source.apply { writeText("First model result") }, "Model A", recording.id, pinned = true)
        val second = app.transcriptHistory.save(source.apply { writeText("Second model result with better accuracy") }, "Model B",
            recording.id, pinned = true, attempt = source.name + "-second")
        var screen: Activity? = null
        fun open(transcript: Boolean) {
            ui.prepare()
            screen = instrumentation.startActivitySync(Intent(app, TranscribeActivity::class.java)
                .putExtra(if (transcript) TranscribeActivity.EXTRA_TRANSCRIPT_ID else TranscribeActivity.EXTRA_AUDIO_ID,
                    if (transcript) first.id else recording.id)
                .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        override fun close() {
            screen?.let { instrumentation.runOnMainSync { it.finish() } }
            app.recordingHistory.delete(recording.id)
            app.transcriptHistory.delete(first.id); app.transcriptHistory.delete(second.id)
            source.delete()
        }
    }

    @Test fun audioOriginLoadsAnExistingLinkedTranscript() = runBlocking {
        Fixture().use { fixture ->
            val owners = ViewModelStore()
            lateinit var model: TranscriptionDialogModel
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, DialogInput(audioId = fixture.recording.id))
                owners.put("dialog", model)
            }
            try {
                val loaded = withTimeout(5000) { model.state.first { !it.importing } }
                assertTrue("Audio history must expose its generated text without running a model again", loaded.preview.isNotEmpty())
                assertTrue(loaded.transcriptId in setOf(fixture.first.id, fixture.second.id))
            } finally { instrumentation.runOnMainSync { owners.clear() } }
        }
    }

    @Test fun recoveryRestoresAudioAssociationEvenWithoutATextHistoryEntry() = runBlocking {
        Fixture().use { fixture ->
            val directory = File(app.cacheDir, "transcripts").apply { mkdirs() }
            val working = TranscriptStore(File.createTempFile("recover-d-", ".txt", directory))
            working.attachSource(TranscriptSource(fixture.recording.id, modelName = "Recovery model"))
            working.append("Recover this result and its audio link")
            working.keepForRecovery()
            val owners = ViewModelStore()
            lateinit var model: TranscriptionDialogModel
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, DialogInput(transcriptPath = working.file.absolutePath))
                owners.put("dialog", model)
            }
            try {
                val loaded = withTimeout(5000) { model.state.first { !it.importing } }
                assertEquals(fixture.recording.id, loaded.audio?.id)
                assertEquals("Recovery model", loaded.model)
            } finally {
                instrumentation.runOnMainSync { owners.clear() }
                TranscriptStore.deleteArtifacts(working.file)
            }
        }
    }

    @Test fun transcriptDeletionCleansSelectedRecoveryCopiesButPreservesUnselectedVersions() = runBlocking {
        for (single in listOf(false, true)) Fixture().use { fixture ->
            val directory = File(app.cacheDir, "transcripts").apply { mkdirs() }
            fun working(entry: TranscriptEntry): TranscriptStore {
                val file = File.createTempFile("version-d-", ".txt", directory)
                entry.file.copyTo(file, overwrite = true)
                return TranscriptStore(file).also { it.attachSource(TranscriptSource(fixture.recording.id, entry.id, entry.model)) }
            }
            val old = working(fixture.first).also { it.keepForRecovery() }
            val next = working(fixture.second)
            val owners = ViewModelStore()
            lateinit var model: TranscriptionDialogModel
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, DialogInput(audioId = fixture.recording.id,
                    transcriptPath = old.file.absolutePath, transcriptOrigin = single))
                owners.put("dialog", model)
            }
            try {
                withTimeout(5000) { model.state.first { !it.importing } }
                // Exercise the actual publication boundary used by retranscription,
                // without loading an ASR model merely to generate fixture text.
                withContext(Dispatchers.IO) {
                    model.javaClass.getDeclaredMethod("exposeStore", TranscriptStore::class.java)
                        .apply { isAccessible = true }.invoke(model, next)
                }
                instrumentation.runOnMainSync { model.requestDeletion() }
                withTimeout(5000) { model.state.first { it.deletion != null } }
                instrumentation.runOnMainSync { model.chooseDeletion(HistoryDeletionTarget.TRANSCRIPTS); model.confirmDeletion() }
                withTimeout(5000) { model.state.first { !it.deleting && it.preview.isEmpty() } }
                assertEquals("Only confirmed recovery versions may disappear", single, old.file.exists())
                assertFalse(next.file.exists())
                assertTrue(fixture.recording.part(0).exists())
                assertEquals(single, fixture.first.file.exists())
            } finally {
                instrumentation.runOnMainSync { owners.clear() }
                TranscriptStore.deleteArtifacts(old.file); TranscriptStore.deleteArtifacts(next.file)
            }
        }
    }

    @Test fun failedAttemptPublishesTheLatestSurvivingWorkingText() = runBlocking {
        assertFalse("Use the model-free QA identity", app.modelManager.isModelReady())
        Fixture().use { fixture ->
            val owners = ViewModelStore()
            lateinit var model: TranscriptionDialogModel
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, DialogInput(audioId = fixture.recording.id))
                owners.put("dialog", model)
            }
            try {
                val loaded = withTimeout(5000) { model.state.first { !it.importing } }
                val store = checkNotNull(loaded.store)
                withContext(Dispatchers.IO) { store.append("Latest preserved segment") }
                instrumentation.runOnMainSync { model.retry() }
                val failed = withTimeout(10000) { model.state.first { !it.running && it.capture.phase == io.github.lrq3000.utterlane.asr.CapturePhase.FAILED } }
                assertEquals(store.preview(), failed.preview)
                assertEquals(store.bytes, failed.transcriptBytes)
            } finally { instrumentation.runOnMainSync { owners.clear() } }
        }
    }

    @Test fun transcriptOriginConfirmationDeletesOnlyTheSelectedVersion() = runBlocking {
        Fixture().use { fixture ->
            fixture.open(transcript = true)
            ui.click("dialog_delete"); ui.click("delete_choice_transcripts")
            ui.textNode("Delete this transcript?").recycle()
            ui.click("delete_cancel")
            assertTrue(fixture.first.file.exists()); assertTrue(fixture.second.file.exists())
            ui.click("dialog_delete"); ui.click("delete_choice_transcripts"); ui.click("delete_confirm")
            await { !fixture.first.file.exists() }
            assertTrue(fixture.second.file.exists())
            assertTrue(fixture.recording.part(0).exists())
        }
    }

    @Test fun transcriptOriginBothPreservesSiblingVersions() = runBlocking {
        Fixture().use { fixture ->
            fixture.open(transcript = true)
            ui.click("dialog_delete"); ui.click("delete_choice_both")
            ui.textNode("Delete the audio recording and this transcript?").recycle()
            ui.click("delete_confirm")
            await { !fixture.recording.directory.exists() && !fixture.first.file.exists() }
            assertTrue(fixture.second.file.exists())
        }
    }

    @Test fun audioOriginBothClearlyConfirmsAllLinkedVersions() = runBlocking {
        Fixture().use { fixture ->
            fixture.open(transcript = false)
            ui.click("dialog_delete"); ui.click("delete_choice_both")
            ui.textNode("Delete the audio recording and all 2 linked transcripts?").recycle()
            ui.textNode("This deletes all linked transcripts, including versions made with other models. To delete an individual version, open Transcript history and select that transcript.").recycle()
            ui.click("delete_confirm")
            await { !fixture.recording.directory.exists() && !fixture.first.file.exists() && !fixture.second.file.exists() }
        }
    }

    @Test fun missingAudioSkipsChoiceButStillRequiresConfirmation() = runBlocking {
        Fixture().use { fixture ->
            app.recordingHistory.delete(fixture.recording.id)
            fixture.open(transcript = true)
            ui.click("dialog_delete")
            ui.textNode("Delete this transcript?").recycle()
            assertTrue(fixture.first.file.exists())
            ui.click("delete_confirm")
            await { !fixture.first.file.exists() }
            assertTrue(fixture.second.file.exists())
        }
    }

    @Test fun audioOnlySkipsChoiceButStillRequiresConfirmation() = runBlocking {
        Fixture().use { fixture ->
            app.transcriptHistory.delete(fixture.first.id); app.transcriptHistory.delete(fixture.second.id)
            fixture.open(transcript = false)
            ui.click("dialog_delete")
            ui.textNode("Delete this audio recording?").recycle()
            assertTrue(fixture.recording.part(0).exists())
            ui.click("delete_cancel")
            assertTrue(fixture.recording.part(0).exists())
        }
    }

    @Test fun newlyCreatedSiblingRequiresAnUpdatedConfirmation() = runBlocking {
        Fixture().use { fixture ->
            fixture.open(transcript = false)
            ui.click("dialog_delete"); ui.click("delete_choice_both")
            ui.textNode("Delete the audio recording and all 2 linked transcripts?").recycle()
            val file = File.createTempFile("new-sibling-", ".txt", app.cacheDir).apply { writeText("A later model result") }
            val later = app.transcriptHistory.save(file, "Later model", fixture.recording.id, pinned = true)
            try {
                ui.click("delete_confirm")
                ui.textNode("Delete the audio recording and all 3 linked transcripts?").recycle()
                assertTrue(fixture.recording.part(0).exists())
                assertTrue(fixture.first.file.exists()); assertTrue(fixture.second.file.exists()); assertTrue(later.file.exists())
                ui.click("delete_cancel")
            } finally { app.transcriptHistory.delete(later.id); file.delete() }
        }
    }

    @Test fun dLayoutKeepsActionsInTheirRowsAndPinReallyToggles() = runBlocking {
        Fixture().use { fixture ->
            fixture.open(transcript = true)
            pinChoice("pin_choice_transcript", "Unpin transcript")
            await { !app.transcriptHistory.get(fixture.first.id).retention.pinned }
            pinChoice("pin_choice_transcript", "Pin transcript")
            await { app.transcriptHistory.get(fixture.first.id).retention.pinned }
            val back = bounds("dialog_back")
            val delete = bounds("dialog_delete")
            val pin = bounds("dialog_pin")
            val copy = bounds("dialog_copy")
            assertTrue("Header actions share a row", kotlin.math.abs(back.centerY() - delete.centerY()) <= 2)
            assertTrue("Transcript actions belong below header tools", pin.centerY() > delete.bottom)
            assertTrue("Bottom actions share a row", kotlin.math.abs(pin.centerY() - copy.centerY()) <= 2)
            ui.screenshot("transcription-dialog-d")
        }
    }

    @Test fun pinMenuSupportsIndependentAndCombinedRetention() = runBlocking {
        Fixture().use { fixture ->
            fixture.open(transcript = true)
            pinChoice("pin_choice_both", "Unpin both")
            await { !app.recordingHistory.get(fixture.recording.id).pinned && !app.transcriptHistory.get(fixture.first.id).retention.pinned }
            assertTrue("Pin scope is the displayed transcript, not sibling versions", app.transcriptHistory.get(fixture.second.id).retention.pinned)
            pinChoice("pin_choice_audio", "Pin audio recording")
            await { app.recordingHistory.get(fixture.recording.id).pinned }
            assertFalse(app.transcriptHistory.get(fixture.first.id).retention.pinned)
            pinChoice("pin_choice_both", "Pin both")
            await { app.transcriptHistory.get(fixture.first.id).retention.pinned }
            pinChoice("pin_choice_transcript", "Unpin transcript")
            await { !app.transcriptHistory.get(fixture.first.id).retention.pinned }
            assertTrue(app.recordingHistory.get(fixture.recording.id).pinned)
            ui.click("dialog_pin")
            ui.textNode("Unpin audio recording").recycle()
            ui.textNode("Pin transcript").recycle()
            ui.screenshot("transcription-dialog-d-pin-menu")
        }
    }

    @Test fun pinMenuOnlyOffersAvailableData() = runBlocking {
        Fixture().use { fixture ->
            app.recordingHistory.delete(fixture.recording.id)
            fixture.open(transcript = true)
            ui.click("dialog_pin")
            ui.textNode("Unpin transcript").recycle()
            assertFalse(ui.hasVisibleText("Pin audio recording"))
            assertFalse(ui.hasVisibleText("Unpin audio recording"))
            assertFalse(ui.hasVisibleText("Pin both"))
            assertFalse(ui.hasVisibleText("Unpin both"))
            ui.click("pin_choice_transcript")
            await { !app.transcriptHistory.get(fixture.first.id).retention.pinned }
        }
        Fixture().use { fixture ->
            app.transcriptHistory.delete(fixture.first.id); app.transcriptHistory.delete(fixture.second.id)
            fixture.open(transcript = false)
            ui.click("dialog_pin")
            ui.textNode("Unpin audio recording").recycle()
            assertFalse(ui.hasVisibleText("Pin transcript"))
            assertFalse(ui.hasVisibleText("Unpin transcript"))
            assertFalse(ui.hasVisibleText("Unpin both"))
            ui.click("pin_choice_audio")
            await { !app.recordingHistory.get(fixture.recording.id).pinned }
        }
    }

    @Test fun unpinningARemovedTranscriptDoesNotCreateANewSavedVersion() = runBlocking {
        Fixture().use { fixture ->
            val owners = ViewModelStore()
            lateinit var model: TranscriptionDialogModel
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, DialogInput(transcriptId = fixture.first.id))
                owners.put("dialog", model)
            }
            try {
                withTimeout(5000) { model.state.first { !it.importing } }
                app.transcriptHistory.delete(fixture.first.id)
                instrumentation.runOnMainSync { model.setPinned(DialogPinTarget.TRANSCRIPT, false) }
                withTimeout(5000) { model.state.first { !it.saving } }
                assertEquals(setOf(fixture.second.id), app.transcriptHistory.forAudio(fixture.recording.id).map { it.id }.toSet())
            } finally {
                instrumentation.runOnMainSync { owners.clear() }
                app.transcriptHistory.forAudio(fixture.recording.id).forEach { app.transcriptHistory.delete(it.id) }
            }
        }
    }

    private suspend fun pinChoice(id: String, label: String) {
        await { val node = ui.node("dialog_pin"); try { node.isEnabled } finally { node.recycle() } }
        ui.click("dialog_pin")
        ui.textNode(label).recycle()
        ui.click(id)
    }

    @Test fun narrowHeaderKeepsFullTargetsAndRestoresTheLargerTitle() = runBlocking {
        val theme = app.settingsRepository.themeMode.first()
        try {
            Fixture().use { fixture ->
                fixture.open(transcript = true)
                val density = app.resources.displayMetrics.density
                for (width in listOf(320, 392)) {
                    instrumentation.runOnMainSync {
                        fixture.screen!!.window.setLayout((width * density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
                    }
                    await { fixture.screen!!.window.decorView.width == (width * density).toInt() }
                    val back = bounds("dialog_back")
                    val retry = bounds("dialog_retranscribe")
                    val title = bounds("dialog_title")
                    val delete = bounds("dialog_delete")
                    assertTrue(kotlin.math.abs(back.centerY() - delete.centerY()) <= 2)
                    assertTrue("Title must fit between the icon groups", title.left >= back.right && title.right <= retry.left)
                    assertTrue("Icon targets must remain 48 dp", back.width() >= 48 * density - 2)
                    ui.screenshot("transcription-dialog-d-$width-light")
                }
                app.settingsRepository.setThemeMode(SettingsRepository.THEME_DARK)
                ui.screenshot("transcription-dialog-d-dark")
            }
        } finally { app.settingsRepository.setThemeMode(theme) }
    }

    @Test fun longReaderCanJumpToTheEndAndBackWithoutPageButtons() = runBlocking {
        Fixture().use { fixture ->
            val file = File.createTempFile("long-dialog-d-", ".txt", app.cacheDir)
            file.writeText(buildString {
                repeat(4000) { append("Paragraph $it: multilingual text déjà vu 日本語 🎙️ with enough words to scroll.\n") }
                append("END OF COMPLETE TRANSCRIPT")
            })
            val entry = app.transcriptHistory.save(file, "Long result", fixture.recording.id, pinned = true)
            try {
                ui.prepare()
                fixture.screen = instrumentation.startActivitySync(Intent(app, TranscribeActivity::class.java)
                    .putExtra(TranscribeActivity.EXTRA_TRANSCRIPT_ID, entry.id)
                    .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ui.node("transcript_chunk_0").recycle()
                val document = TranscriptDocument(file)
                var last = document.chunkCount - 1
                while (document.read(last).isEmpty()) last--
                setScrollProgress(1f)
                val tail = ui.node("transcript_chunk_$last")
                assertTrue(tail.text.toString().contains("END OF COMPLETE TRANSCRIPT"))
                tail.recycle()
                setScrollProgress(0f)
                val head = ui.node("transcript_chunk_0")
                assertTrue(head.text.toString().startsWith("Paragraph 0:"))
                head.recycle()
                assertFalse(ui.hasVisibleText(app.getString(R.string.stream_previous)))
                assertFalse(ui.hasVisibleText(app.getString(R.string.stream_next)))
            } finally { app.transcriptHistory.delete(entry.id); file.delete() }
        }
    }

    private fun setScrollProgress(value: Float) {
        val node = ui.node("transcript_scrollbar")
        try {
            val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
            assertTrue(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
        } finally { node.recycle() }
    }

    private fun bounds(id: String): Rect {
        val node = ui.node(id)
        return try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(20) }
}
