package io.github.lrq3000.utterlane

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.service.RecordingRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingRecoveryAndroidTest {
    @Test fun retryWithAnotherNativeModelProducesTextFromThePreservedRecording(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        ParallelCaptureFixtures.installTernary(app)
        val previousModel = app.modelManager.selected.value
        val previousDiarization = app.settingsRepository.diarizationEnabled.first()
        val pcm = ParallelCaptureFixtures.speech(app)
        val audio = withContext(Dispatchers.IO) {
            app.microphoneRecordings.begin(HistoryRetention.NONE).also {
                it.append(pcm); it.finish(true)
                app.microphoneRecordings.recover(it, "Previous model unavailable", modelFailure = true)
            }
        }
        val id = audio.entry.id
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, id))
        try {
            awaitText(app.getString(R.string.recording_model_failed))
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(ModelCatalog.REDUX_TERNARY)
            app.settingsRepository.setDiarizationEnabled(false)
            click(app.getString(R.string.recording_retry))
            awaitText("Alice", timeout = 120000)
            awaitText(app.getString(R.string.transcribe_copy))
            assertEquals(pcm.size.toLong(), audio.samples)
            assertTrue(audio.entry.directory.exists())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            withTimeout(5000) { while (audio.entry.directory.exists()) delay(20) }
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(previousModel)
            app.settingsRepository.setDiarizationEnabled(previousDiarization)
        }
    }

    @Test fun modelSelectionAndFailedRetryKeepAudioUntilRecoveryIsDismissed(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        assertTrue(app.packageName.endsWith(".parallelcapture"))
        val audio = withContext(Dispatchers.IO) {
            app.microphoneRecordings.begin(HistoryRetention.NONE).also {
                it.append(shortArrayOf(2, 4, 6, 8)); it.finish(true)
                app.microphoneRecordings.recover(it, "Injected model preparation failure", modelFailure = true)
            }
        }
        val id = audio.entry.id
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, id))
        try {
            awaitText(app.getString(R.string.recording_model_failed))
            click(app.getString(R.string.recording_choose_model))
            awaitText(app.getString(R.string.model_choose))
            // The picker itself, not just the settings page, must be open.
            awaitText(ModelCatalog.PARAKEET_V3.name)
            assertArrayEquals(shortArrayOf(2, 4, 6, 8), withContext(Dispatchers.IO) { audio.read(0, 4) })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText(app.getString(R.string.recording_retry))
            click(app.getString(R.string.recording_retry))
            awaitText(app.getString(R.string.transcribe_error_no_model))
            assertSame(audio, app.microphoneRecordings.get(id))
            assertArrayEquals(shortArrayOf(2, 4, 6, 8), withContext(Dispatchers.IO) { audio.read(0, 4) })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            withTimeout(5000) { while (id in app.microphoneRecordings.pending.value) delay(20) }
            withTimeout(5000) { while (audio.entry.directory.exists()) delay(20) }
        }
    }

    private suspend fun awaitText(text: String, timeout: Long = 8000): AccessibilityNodeInfo {
        try {
            return withTimeout(timeout) {
                while (true) {
                    // Traverse virtual Compose descendants too: Android's framework
                    // text-search shortcut does not cover every accessibility provider.
                    val found = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.contains(text) == true }
                    if (found != null) return@withTimeout found
                    delay(30)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Missing visible text: $text; visible nodes: ${nodes().mapNotNull { it.text }.joinToString(" | ")}", e)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(pending::add)
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        return result
    }

    private suspend fun click(text: String) {
        var node: AccessibilityNodeInfo? = awaitText(text)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("No clickable owner for $text", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
