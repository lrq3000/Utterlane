package io.github.lrq3000.utterlane.settings

import io.github.lrq3000.utterlane.audio.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Exercise real repository transactions; only the device inventory boundary is mocked. */
class MicrophoneSettingsActionsTest {
    private val settings = SettingsRepository(MemoryStore())
    private val inputs = mockk<AudioInputController>()
    private var requests = 0
    private val actions = MicrophoneSettingsActions(settings, inputs) { requests++ }
    private val headset = AudioInput("headset", "Headset", true, 7)

    @Test fun defaultAndCustomEntryDoNotRequestAndCustomRetainsEveryValue() = runBlocking {
        assertEquals(MicrophoneSettings(), settings.microphoneSettings.first())
        assertEquals(0, requests)
        actions.selectPreset(MicrophonePreset.DISABLED)
        actions.selectPreset(MicrophonePreset.CUSTOM)
        assertEquals(MicrophoneSettings(MicrophonePreset.CUSTOM, MicrophoneOptions.STANDARD),
            settings.microphoneSettings.first())
        assertEquals(0, requests)
    }

    @Test fun explicitHfpAndResetWriteExactTupleBeforeRequesting() = runBlocking {
        val checked = MicrophoneSettingsActions(settings, inputs) {
            assertEquals(MicrophoneSettings(), runBlocking { settings.microphoneSettings.first() })
            requests++
        }
        checked.selectPreset(MicrophonePreset.DISABLED)
        checked.selectPreset(MicrophonePreset.HFP_PRESET)
        checked.updateOptions { it.copy(gain = PcmGainMode.DB_PLUS_12) }
        checked.reset()
        assertEquals(2, requests)
    }

    @Test fun independentEditsRetainLatestValuesAndOnlyHfpRouteRequests() = runBlocking {
        actions.selectPreset(MicrophonePreset.DISABLED)
        actions.updateOptions { it.copy(source = MicrophoneSource.UNPROCESSED) }
        actions.updateOptions { it.copy(gain = PcmGainMode.DB_PLUS_18) }
        actions.selectRoute(BluetoothCaptureRoute.HFP_VOICE_RECOGNITION)
        val result = settings.microphoneSettings.first()
        assertEquals(MicrophonePreset.CUSTOM, result.preset)
        assertEquals(MicrophoneSource.UNPROCESSED, result.options.source)
        assertEquals(PcmGainMode.DB_PLUS_18, result.options.gain)
        assertEquals(BluetoothAudioMode.IN_COMMUNICATION, result.options.mode)
        assertEquals(1, requests)
        actions.selectRoute(BluetoothCaptureRoute.STANDARD_SCO)
        assertEquals(1, requests)
    }

    @Test fun permissionDenialCannotUndoSavedInputOrAutoPreference() = runBlocking {
        coEvery { inputs.select(headset.key) } coAnswers {
            settings.updateAudioInput { it.copy(selectedKey = headset.key) }; true
        }
        coEvery { inputs.setPreferBluetooth(any()) } coAnswers {
            settings.updateAudioInput { it.copy(preferBluetooth = firstArg()) }
            AudioInputState(listOf(headset), settings.audioInputPreferences.first())
        }
        // The request callback intentionally grants nothing, as with a denied dialog.
        assertTrue(actions.selectInput(headset))
        actions.setPreferBluetooth(true)
        assertEquals(InputPreferences(headset.key, true), settings.audioInputPreferences.first())
        assertEquals(2, requests)
        actions.setPreferBluetooth(false)
        assertEquals(2, requests)
    }

    @Test fun phoneUsbAndDisappearedBluetoothDoNotRequest() = runBlocking {
        coEvery { inputs.select(any()) } returns true
        actions.selectInput(AudioInput(AudioInput.PHONE_KEY, "Phone", false, 1))
        actions.selectInput(AudioInput("usb", "USB", false, 2))
        coEvery { inputs.select(headset.key) } returns false
        assertFalse(actions.selectInput(headset))
        assertEquals(0, requests)
    }

    @Test fun inputPermissionDecisionReadsSettingsAfterSuspendedSelection() = runBlocking {
        for (latestPreset in listOf(MicrophonePreset.DISABLED, MicrophonePreset.HFP_PRESET)) {
            settings.setMicrophonePreset(if (latestPreset == MicrophonePreset.DISABLED)
                MicrophonePreset.HFP_PRESET else MicrophonePreset.DISABLED)
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            coEvery { inputs.select(headset.key) } coAnswers { entered.complete(Unit); resume.await(); true }
            val selection = async { actions.selectInput(headset) }
            entered.await()
            settings.setMicrophonePreset(latestPreset)
            resume.complete(Unit)
            assertTrue(selection.await())
        }
        assertEquals("Only the selection committed with HFP configured may request", 1, requests)
    }

    @Test fun autoPermissionDecisionReadsSettingsAfterSuspendedWrite() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        coEvery { inputs.setPreferBluetooth(true) } coAnswers {
            entered.complete(Unit); resume.await()
            AudioInputState(listOf(headset), InputPreferences(headset.key, true))
        }
        val preference = async { actions.setPreferBluetooth(true) }
        entered.await()
        settings.setMicrophonePreset(MicrophonePreset.DISABLED)
        resume.complete(Unit)
        preference.await()
        assertEquals(0, requests)
    }
}
