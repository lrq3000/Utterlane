package io.github.lrq3000.utterlane.ui

import android.app.Application
import android.view.View
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.CapturePhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class KeyboardSettingsPromptTest {
    @Test fun firstTapAndBackLeaveCaptureAlone() {
        val actions = mutableListOf<CapturePhase>()
        val prompt = KeyboardSettingsPrompt(RuntimeEnvironment.getApplication(), actions::add)
        prompt.update(CapturePhase.CAPTURING)
        prompt.gear.performClick()
        assertEquals(View.VISIBLE, prompt.visibility)
        assertTrue(actions.isEmpty())
        prompt.findViewById<View>(R.id.recording_settings_back).performClick()
        assertEquals(View.GONE, prompt.visibility)
        assertTrue(actions.isEmpty())
    }

    @Test fun confirmingRecordingAndProcessingAreDifferentActions() {
        val actions = mutableListOf<CapturePhase>()
        val prompt = KeyboardSettingsPrompt(RuntimeEnvironment.getApplication(), actions::add)
        for (phase in listOf(CapturePhase.CAPTURING, CapturePhase.PROCESSING)) {
            prompt.update(phase)
            prompt.gear.performClick()
            prompt.findViewById<View>(R.id.recording_settings_confirm).performClick()
        }
        assertEquals(listOf(CapturePhase.CAPTURING, CapturePhase.PROCESSING), actions)
    }

    @Test fun aRecordingWarningCannotDiscardAfterCaptureEnds() {
        val actions = mutableListOf<CapturePhase>()
        val prompt = KeyboardSettingsPrompt(RuntimeEnvironment.getApplication(), actions::add)
        prompt.update(CapturePhase.CAPTURING)
        prompt.gear.performClick()
        prompt.update(CapturePhase.PROCESSING)
        assertEquals(View.GONE, prompt.visibility)
        prompt.findViewById<View>(R.id.recording_settings_confirm).performClick()
        assertTrue(actions.isEmpty())
    }
}
