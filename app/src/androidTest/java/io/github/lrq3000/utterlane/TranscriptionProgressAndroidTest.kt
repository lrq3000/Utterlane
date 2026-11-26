package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.lrq3000.utterlane.asr.StreamingCorrections
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.asr.TranscriptDocument
import io.github.lrq3000.utterlane.asr.TranscriptionSession
import io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.transcribe.*
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose dialog geometry and reading continuity; deterministic progress
 * fixtures avoid measuring native inference speed in a layout regression test. */
@RunWith(AndroidJUnit4::class)
class TranscriptionProgressAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    private inner class Fixture(paragraphs: Int = 1000) : AutoCloseable {
        private val recording = app.recordingHistory.begin(HistoryRetention.DAY).also {
            it.append(ShortArray(16000)); it.finish(false)
        }.entry
        private val text = File.createTempFile("progress-d-", ".txt", app.cacheDir).apply {
            writeText(buildString { repeat(paragraphs) { append("Paragraph $it: Read these already transcribed words comfortably while the rest of the audio is processed.\n\n") } })
        }
        private val entry = app.transcriptHistory.save(text, "Progress fixture", recording.id, pinned = true)
        val activity: Activity
        val model: TranscriptionDialogModel
        private val mutable: MutableStateFlow<TranscriptionDialogState>
        init {
            ui.prepare()
            activity = instrumentation.startActivitySync(Intent(app, TranscribeActivity::class.java)
                .putExtra(TranscribeActivity.EXTRA_TRANSCRIPT_ID, entry.id)
                .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            model = activity.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(activity) as TranscriptionDialogModel
            @Suppress("UNCHECKED_CAST")
            mutable = model.javaClass.getDeclaredField("mutable").apply { isAccessible = true }.get(model) as MutableStateFlow<TranscriptionDialogState>
            runBlocking { withTimeout(5000) { model.state.first { !it.importing && it.transcriptBytes > 0 } } }
            ui.node("transcript_reader").recycle()
        }
        fun show(progress: FileProgressSnapshot, message: String? = null) {
            instrumentation.runOnMainSync {
                mutable.value = mutable.value.copy(fileProgress = progress, message = message,
                    running = progress.stage !in setOf(FileProgressStage.COMPLETE, FileProgressStage.FAILED, FileProgressStage.CANCELLED))
            }
            instrumentation.waitForIdleSync()
        }
        override fun close() {
            instrumentation.runOnMainSync { activity.finish() }
            app.recordingHistory.delete(recording.id); app.transcriptHistory.delete(entry.id); text.delete()
        }
    }

    private fun measured() = FileProgressSnapshot(FileProgressStage.TRANSCRIBING,
        processedSamples = 320 * 16000L, totalSamples = 480 * 16000L, percent = 66, remainingSeconds = 42.0)

    @Test fun percentageAndEtaAreVisibleByDefaultBelowTheText() = runBlocking {
        val previous = app.settingsRepository.showTranscriptionStreamStatistics.first()
        app.settingsRepository.setShowTranscriptionStreamStatistics(false)
        try {
            Fixture().use { fixture ->
                fixture.show(measured())
                assertEquals("66%", text("transcription_percentage"))
                assertTrue(text("transcription_eta").contains("42"))
                val reader = bounds("transcript_viewport")
                val progress = bounds("transcription_progress")
                assertTrue("The dock belongs below the text, not above it", progress.top >= reader.bottom - 2)
                assertEquals("Known duration belongs beside the percentage", bounds("transcription_percentage").centerY().toDouble(),
                    bounds("transcription_audio_progress").centerY().toDouble(), 5.0 * app.resources.displayMetrics.density)
                assertTrue(bounds("dialog_copy").top >= progress.bottom - 2)
                ui.screenshot("transcription-progress-d-light")
            }
        } finally { app.settingsRepository.setShowTranscriptionStreamStatistics(previous) }
    }

    @Test fun completionExpandsDownwardWithoutMovingTheReadersTopOrScrollAnchor() = runBlocking {
        Fixture().use { fixture ->
            fixture.show(measured().copy(stage = FileProgressStage.SPEAKERS, percent = null, remainingSeconds = null))
            ui.node("transcription_progress_bar").recycle()
            val scroll = ui.node("transcript_scrollbar")
            try {
                assertTrue(scroll.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0.25f) }))
            } finally { scroll.recycle() }
            withTimeout(5000) { while (scrollPosition() < 0.20f) delay(50) }
            instrumentation.waitForIdleSync()
            val before = bounds("transcript_viewport")
            val position = scrollPosition()
            fixture.show(measured().copy(stage = FileProgressStage.COMPLETE, percent = 100, remainingSeconds = null))
            assertEquals("100%", text("transcription_percentage"))
            val after = bounds("transcript_viewport")
            assertEquals("Top edge must remain anchored", before.top, after.top)
            assertTrue("Completion returns space below the document", after.bottom > before.bottom)
            assertEquals("Keep the reader's location", position, scrollPosition(), 0.005f)
            ui.screenshot("transcription-progress-d-complete")
        }
    }

    @Test fun unknownDurationAndInterruptedWorkKeepTextReadable() = runBlocking {
        Fixture().use { fixture ->
            fixture.show(measured().copy(totalSamples = null, percent = null, remainingSeconds = null))
            assertTrue(text("transcription_audio_progress").contains("5:20"))
            assertFalse(text("transcription_audio_progress").contains("8:00"))
            fixture.show(measured().copy(stage = FileProgressStage.FAILED, percent = null, remainingSeconds = null), "Fixture inference failure")
            ui.node("transcript_reader").recycle()
            assertTrue(text("transcription_stage").contains("interrupted", ignoreCase = true))
            ui.textNode("Fixture inference failure").recycle()
        }
    }

    @Test fun completionPreservesTheTextAnchorAtTheEndOfTheDocument() = runBlocking {
        // One loaded text chunk isolates viewport anchoring from asynchronous
        // paging jumps; the separate long-document test exercises paging.
        for (backoff in listOf(0f, 0.01f)) Fixture(paragraphs = 12).use { fixture ->
            fixture.show(measured().copy(stage = FileProgressStage.SPEAKERS, percent = null, remainingSeconds = null))
            ui.node("transcription_progress_bar").recycle()
            val document = TranscriptDocument(fixture.model.state.value.store!!.file)
            var last = document.chunkCount - 1
            while (document.read(last).isEmpty()) last--
            val scrollbar = ui.node("transcript_scrollbar")
            try {
                assertTrue(scrollbar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1f) }))
            } finally { scrollbar.recycle() }
            ui.node("transcript_chunk_$last").recycle()
            withTimeout(5000) { while (scrollPosition() == 0f) delay(50) }
            if (backoff > 0) {
                val end = scrollPosition()
                val nearEnd = ui.node("transcript_scrollbar")
                try {
                    assertTrue(nearEnd.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, end - backoff) }))
                } finally { nearEnd.recycle() }
                withTimeout(5000) { while (scrollPosition() >= end) delay(50) }
            }
            ui.screenshot("transcription-progress-d-eof-before")
            val before = scrollPosition()
            val viewport = bounds("transcript_viewport")
            fixture.show(measured().copy(stage = FileProgressStage.COMPLETE, percent = 100, remainingSeconds = null))
            ui.screenshot("transcription-progress-d-eof-after")
            assertTrue(bounds("transcript_viewport").height() > viewport.height())
            assertEquals("Completion must not pull earlier words into view at EOF", before, scrollPosition(), 0.0000001f)
        }
    }

    @Test fun narrowDockFitsInLightAndDarkThemes() = runBlocking {
        val previous = app.settingsRepository.themeMode.first()
        try {
            Fixture().use { fixture ->
                val density = app.resources.displayMetrics.density
                instrumentation.runOnMainSync {
                    fixture.activity.window.setLayout((320 * density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
                }
                for (theme in listOf(SettingsRepository.THEME_LIGHT, SettingsRepository.THEME_DARK)) {
                    app.settingsRepository.setThemeMode(theme)
                    fixture.show(measured().copy(estimatedTotal = true, additionalFinishing = true))
                    val dock = bounds("transcription_progress")
                    for (id in listOf("transcription_stage", "transcription_percentage", "transcription_eta", "transcription_audio_progress")) {
                        val item = bounds(id)
                        assertTrue("$id must fit inside the dock", item.left >= dock.left && item.right <= dock.right && item.bottom <= dock.bottom)
                    }
                    assertTrue(text("transcription_percentage").contains("≈"))
                    assertTrue(text("transcription_eta").contains("finishing"))
                    ui.screenshot("transcription-progress-d-320-$theme")
                }
            }
        } finally { app.settingsRepository.setThemeMode(previous) }
    }

    @Test fun largeFontWrapsWithinTheNarrowDock() = runBlocking {
        Fixture().use { fixture ->
            val density = app.resources.displayMetrics.density
            instrumentation.runOnMainSync {
                fixture.activity.window.setLayout((320 * density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
                // Override only this composition, leaving the shared emulator's
                // global font/accessibility settings available to other agents.
                (fixture.activity as ComponentActivity).setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f)) {
                        UtterlaneTheme(darkTheme = false) { TranscriptionDialog(fixture.model, {}, {}) }
                    }
                }
            }
            fixture.show(measured().copy(estimatedTotal = true, additionalFinishing = true))
            val dock = bounds("transcription_progress")
            for (id in listOf("transcription_eta", "transcription_audio_progress")) {
                val item = bounds(id)
                assertTrue("Large-font $id must remain visible", item.left >= dock.left && item.right <= dock.right && item.bottom <= dock.bottom)
            }
            assertTrue(bounds("transcript_viewport").height() > 100 * density)
            ui.screenshot("transcription-progress-d-large-font")
        }
    }

    @Test fun changingReaderSizeReflowsOnlyTheDocumentAndPersistsOnReopen() = runBlocking {
        val settings = app.settingsRepository
        val previous = settings.transcriptTextSize.first()
        try {
            settings.setTranscriptTextSize(16)
            var enlargedHeight = 0
            Fixture(paragraphs = 1).use { fixture ->
                fixture.show(measured())
                val normal = bounds("transcript_chunk_0").height()
                val stageHeight = bounds("transcription_stage").height()
                ui.clickDescription(app.getString(R.string.reader_font_size))
                assertFontChoice(16, checked = true)
                assertFontChoice(24, checked = false)
                ui.click("reader_text_size_24")
                withTimeout(5000) { settings.transcriptTextSize.first { it == 24 } }
                withTimeout(5000) {
                    do { enlargedHeight = bounds("transcript_chunk_0").height(); if (enlargedHeight <= normal) delay(50) }
                    while (enlargedHeight <= normal)
                }
                assertEquals(stageHeight, bounds("transcription_stage").height())
            }
            Fixture(paragraphs = 1).use {
                withTimeout(5000) { while (bounds("transcript_chunk_0").height() != enlargedHeight) delay(50) }
                ui.clickDescription(app.getString(R.string.reader_font_size))
                assertFontChoice(16, checked = false)
                assertFontChoice(24, checked = true)
            }
        } finally { settings.setTranscriptTextSize(previous) }
    }

    private fun assertFontChoice(size: Int, checked: Boolean) {
        val tag = "reader_text_size_$size"
        ui.scrollTo(tag)
        val node = ui.node(tag)
        try {
            assertEquals("android.widget.RadioButton", node.className.toString())
            assertTrue("The radio option must be visible and checkable", node.isVisibleToUser && node.isCheckable)
            if (!checked) assertTrue("An alternative size must accept clicks on its own named node", node.isClickable)
            assertEquals(app.getString(if (size == SettingsRepository.DEFAULT_TRANSCRIPT_TEXT_SIZE) R.string.reader_size_default else R.string.reader_size_sp, size),
                node.contentDescription.toString())
            assertEquals(checked, node.isChecked)
        } finally { node.recycle() }
    }

    @Test fun processingCommandsStayInTheDockAndPlaybackStaysBelowIt() = runBlocking {
        Fixture().use { fixture ->
            val density = app.resources.displayMetrics.density
            val stage = androidx.compose.runtime.mutableStateOf(QueueStage.PROCESSING)
            instrumentation.runOnMainSync {
                fixture.activity.window.setLayout((392 * density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
                (fixture.activity as ComponentActivity).setContent {
                    UtterlaneTheme(darkTheme = false) {
                        TranscriptionDialog(fixture.model, {}, {}, onBackground = {}, jobControls = QueueJobControls(stage.value,
                            { stage.value = QueueStage.PAUSED }, { stage.value = QueueStage.PROCESSING },
                            { stage.value = QueueStage.STOPPED }, {}, {}))
                    }
                }
            }
            fixture.show(measured())
            val dock = bounds("transcription_progress")
            val pause = bounds("queue_pause")
            val bar = bounds("transcription_progress_bar")
            assertTrue(dock.contains(pause))
            assertTrue("On an ordinary phone the job controls sit beside the progress bar", pause.left >= bar.right &&
                bar.centerY() in pause.top..pause.bottom)
            assertTrue(bounds("audio_play").top >= dock.bottom)
            assertTrue(bounds("dialog_background").top >= dock.bottom)
            ui.node("queue_pause").let { node ->
                try {
                    assertEquals(app.getString(R.string.reader_pause_transcription), node.contentDescription?.toString())
                    assertTrue("The named icon must be independently actionable", node.isClickable)
                    assertTrue("Icon action should not render a text button", node.text.isNullOrBlank())
                } finally { node.recycle() }
            }
            ui.click("queue_pause")
            ui.click("queue_continue")
            ui.click("queue_stop")
            ui.node("queue_restart").recycle()
            assertFalse(ui.hasNode("queue_continue"))
            assertFalse(ui.hasNode("queue_stop"))
            for (transient in listOf(QueueStage.PAUSING, QueueStage.STOPPING)) {
                instrumentation.runOnMainSync { stage.value = transient }
                ui.textNode(app.getString(transient.label())).recycle()
                assertFalse(ui.hasNode("queue_restart"))
                assertFalse(ui.hasNode("queue_pause"))
            }
        }
    }

    @Test fun finalizationIsReportedAfterLastProcessedWindowAndBeforeSpeakerDrain() = runBlocking {
        val events = mutableListOf<String>()
        val store = TranscriptStore(File.createTempFile("finishing-progress-", ".txt", app.cacheDir))
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), {}, decode = { error("Unexpected plain ASR") },
            onProcessed = { end, _ -> events.add("processed:$end") }, decodeSpeakers = { events.add("decode"); emptyList() },
            finishSpeakers = { events.add("speakers"); emptyList() }, onClosed = { events.add("closed") })
        try {
            session.accept(ShortArray(16000))
            session.finish { speakers -> assertTrue(speakers); events.add("finalizing") }
            assertEquals(listOf("decode", "processed:16000", "finalizing", "speakers", "closed"), events)
            session.finish { fail("Already finished session notified twice") }
        } finally { session.close(); store.dispose() }
    }

    private fun bounds(id: String): Rect = ui.node(id).let { node ->
        try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
    }
    private fun text(id: String): String = ui.node(id).let { node ->
        try { node.text.toString() } finally { node.recycle() }
    }
    private fun scrollPosition(): Float = ui.node("transcript_scrollbar").let { node ->
        try { node.rangeInfo.current } finally { node.recycle() }
    }
}
