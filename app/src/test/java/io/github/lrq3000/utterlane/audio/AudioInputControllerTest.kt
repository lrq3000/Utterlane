package io.github.lrq3000.utterlane.audio

import io.github.lrq3000.utterlane.settings.MemoryStore
import io.github.lrq3000.utterlane.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AudioInputControllerTest {
    private val phone = AudioInput(AudioInput.PHONE_KEY, "Phone", false, 1)
    private val headset = AudioInput("headset", "Headset", true, 7)

    @Test fun savedSelectionIsReadBeforeFirstReconciliation() = runBlocking {
        withController(InputPreferences(headset.key), listOf(phone, headset)) { controller, _, _ ->
            assertEquals(headset.key, controller.snapshotForRecording().selected.key)
        }
    }

    @Test fun connectionEventsWorkWithoutSettingsScreenAndDoNotResurrectManualChoice() = runBlocking {
        withController(InputPreferences(headset.key), listOf(phone, headset)) { controller, devices, _ ->
            controller.snapshotForRecording()
            devices.replace(listOf(phone))
            await(controller) { it.selected.isPhone }
            devices.replace(listOf(phone, headset))
            assertTrue(controller.snapshotForRecording().selected.isPhone)
            controller.setPreferBluetooth(true)
            assertEquals(headset.key, controller.snapshotForRecording().selected.key)
            devices.replace(listOf(phone))
            await(controller) { it.selected.isPhone && it.preferences.preferBluetooth }
            devices.replace(listOf(phone, headset))
            await(controller) { it.selected.key == headset.key }
        }
    }

    @Test fun stalePickerCannotSelectDisconnectedDeviceOrOverwriteManualPhoneChoice() = runBlocking {
        withController(InputPreferences(preferBluetooth = true), listOf(phone, headset)) { controller, devices, settings ->
            controller.snapshotForRecording()
            devices.replace(listOf(phone))
            assertFalse(controller.select(headset.key))
            assertTrue(controller.select(AudioInput.PHONE_KEY))
            devices.replace(listOf(phone, headset))
            coroutineScope { repeat(8) { launch { controller.refresh() } } }
            assertEquals(InputPreferences(), controller.snapshotForRecording().preferences)
            assertEquals(InputPreferences(), settings.audioInputPreferences.first())
        }
    }

    private suspend fun await(controller: AudioInputController, predicate: (AudioInputState) -> Boolean) =
        withTimeout(5000) { controller.state.filterNotNull().first(predicate) }

    private suspend fun withController(saved: InputPreferences, initial: List<AudioInput>,
        test: suspend (AudioInputController, Devices, SettingsRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(MemoryStore())
        settings.updateAudioInput { saved }
        val devices = Devices(initial)
        val controller = AudioInputController(devices, settings, scope)
        try { test(controller, devices, settings) }
        finally { controller.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private class Devices(@Volatile var connected: List<AudioInput>) : AudioInputDevices {
        private var callback: () -> Unit = {}
        override fun inputs() = connected
        override fun observe(onChanged: () -> Unit) { callback = onChanged }
        fun replace(inputs: List<AudioInput>) { connected = inputs; callback() }
        override fun close() {}
    }
}
