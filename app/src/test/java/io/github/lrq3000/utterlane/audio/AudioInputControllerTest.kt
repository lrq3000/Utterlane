package io.github.lrq3000.utterlane.audio

import io.github.lrq3000.utterlane.settings.MemoryStore
import io.github.lrq3000.utterlane.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class AudioInputControllerTest {
    private val phone = AudioInput(AudioInput.PHONE_KEY, "Phone", false, 1)
    private val headset = AudioInput("headset", "Headset", true, 7)

    @Test fun rapidDisconnectReconnectResetsManualChoiceBeforeRefreshCanRun() = runBlocking {
        withController(InputPreferences(headset.key), listOf(phone, headset), QueuedDispatcher()) { controller, devices, _ ->
            controller.snapshotForRecording()
            devices.replace(listOf(phone))
            devices.replace(listOf(phone, headset))
            assertTrue("A coalesced reconnect must not silently restore a disconnected manual selection",
                controller.snapshotForRecording().selected.isPhone)
        }
    }

    @Test fun changedConnectionPortResetsManualChoiceEvenWhenRemovalDeliveryLags() = runBlocking {
        withController(InputPreferences(headset.key), listOf(phone, headset), QueuedDispatcher()) { controller, devices, _ ->
            controller.snapshotForRecording()
            devices.connected = listOf(phone, headset.copy(inputId = 70))
            assertTrue(controller.snapshotForRecording().selected.isPhone)
        }
    }

    @Test fun automaticPreferenceSelectsReconnectedHeadsetAfterCoalescedLoss() = runBlocking {
        withController(InputPreferences(headset.key, true), listOf(phone, headset), QueuedDispatcher()) { controller, devices, _ ->
            controller.snapshotForRecording()
            devices.replace(listOf(phone))
            devices.replace(listOf(phone, headset.copy(inputId = 70)))
            val next = controller.snapshotForRecording()
            assertEquals(headset.key, next.selected.key)
            assertEquals(70, next.selected.inputId)
            assertTrue(next.preferences.preferBluetooth)
        }
    }

    @Test fun newManualChoiceSupersedesPendingDisconnectEvenForTheSameHeadset() = runBlocking {
        val dispatcher = QueuedDispatcher()
        withController(InputPreferences(headset.key), listOf(phone, headset), dispatcher) { controller, devices, _ ->
            controller.snapshotForRecording()
            devices.replace(listOf(phone))
            devices.replace(listOf(phone, headset))
            assertTrue(controller.select(headset.key))
            dispatcher.drain()
            assertEquals(headset.key, controller.snapshotForRecording().selected.key)
        }
    }

    @Test fun unrelatedRemovalDoesNotResetTheSelectedHeadset() = runBlocking {
        val other = headset.copy(key = "other", inputId = 8)
        withController(InputPreferences(headset.key), listOf(phone, headset, other), QueuedDispatcher()) { controller, devices, _ ->
            controller.snapshotForRecording()
            devices.replace(listOf(phone, headset))
            assertEquals(headset.key, controller.snapshotForRecording().selected.key)
        }
    }

    @Test fun disconnectDuringManualPreferenceWriteStillInvalidatesThatChoice() = runBlocking {
        val store = MemoryStore()
        withController(InputPreferences(), listOf(phone, headset), QueuedDispatcher(), store) { controller, devices, _ ->
            controller.snapshotForRecording()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var pause = true
            store.beforeUpdate = {
                if (pause) { pause = false; entered.complete(Unit); resume.await() }
            }
            val selection = async(start = CoroutineStart.UNDISPATCHED) { controller.select(headset.key) }
            try {
                entered.await()
                devices.replace(listOf(phone))
                devices.replace(listOf(phone, headset))
            } finally { resume.complete(Unit) }
            assertTrue(selection.await())
            assertTrue("A removal after the choice was made must survive its pending disk write",
                controller.snapshotForRecording().selected.isPhone)
        }
    }

    @Test fun failedManualWriteKeepsRemovalOfPreviousSelection() = runBlocking {
        val store = MemoryStore()
        val usb = AudioInput("usb", "USB", false, 9)
        withController(InputPreferences(headset.key), listOf(phone, headset, usb), QueuedDispatcher(), store) { controller, devices, _ ->
            controller.snapshotForRecording()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var pause = true
            store.beforeUpdate = {
                if (pause) {
                    pause = false; entered.complete(Unit); resume.await()
                    throw java.io.IOException("Simulated failed preference write")
                }
            }
            val selection = async(start = CoroutineStart.UNDISPATCHED) {
                try { controller.select(usb.key); null } catch (e: java.io.IOException) { e }
            }
            try {
                entered.await()
                devices.replace(listOf(phone, usb))
                devices.replace(listOf(phone, headset, usb))
            } finally { resume.complete(Unit) }
            assertNotNull(selection.await())
            assertTrue(controller.snapshotForRecording().selected.isPhone)
        }
    }

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
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        store: MemoryStore = MemoryStore(),
        test: suspend (AudioInputController, Devices, SettingsRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val settings = SettingsRepository(store)
        settings.updateAudioInput { saved }
        val devices = Devices(initial)
        val controller = AudioInputController(devices, settings, scope)
        try { test(controller, devices, settings) }
        finally {
            controller.close(); scope.cancel()
            if (dispatcher is QueuedDispatcher) dispatcher.drain()
            scope.coroutineContext[Job]!!.join()
        }
    }

    /** Hold the refresh coroutine while the device callbacks themselves are delivered. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private class Devices(@Volatile var connected: List<AudioInput>) : AudioInputDevices {
        private var callback: (Set<Int>) -> Unit = {}
        override fun inputs() = connected
        override fun observe(onChanged: (Set<Int>) -> Unit) { callback = onChanged }
        fun replace(inputs: List<AudioInput>) {
            val remaining = inputs.mapNotNull { it.connectionId }.toSet()
            val removed = connected.mapNotNull { it.connectionId }.toSet() - remaining
            connected = inputs
            callback(removed)
        }
        override fun close() {}
    }
}
