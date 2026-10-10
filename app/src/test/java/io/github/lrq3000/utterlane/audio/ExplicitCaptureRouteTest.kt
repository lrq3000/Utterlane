package io.github.lrq3000.utterlane.audio

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 36])
class ExplicitCaptureRouteTest {
    @Test fun unrelatedEventDuringNativeInspectionDoesNotEraseWarmupProgress() {
        AudioRoutingPlatform().use { p ->
            every { p.record.bufferSizeInFrames } returns 64000
            every { p.manager.startBluetoothSco() } throws SecurityException("SCO unavailable")
            val headset = p.addHeadset()
            val unrelated = AudioRoutingPlatform.device(77, AudioDeviceInfo.TYPE_USB_DEVICE, "usb", true)
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            repeat(5) { step ->
                if (step == 2) {
                    var deliverDuringQuery = true
                    every { p.record.routedDevice } answers {
                        if (deliverDuringQuery) {
                            deliverDuringQuery = false
                            p.inputDevices = p.inputDevices + unrelated
                            p.changed(added = listOf(unrelated))
                        }
                        p.actualInput
                    }
                }
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                p.capture(route, frames = 16000)
                assertFalse(route.takeReopenRequest())
            }
            assertTrue("A single unrelated inventory race must not restart client-buffer warmup", p.state.receivingFallback)
        }
    }

    @Test fun unrelatedDeviceEventsDoNotRestartHealthyPhoneBufferVerification() {
        AudioRoutingPlatform().use { p ->
            every { p.record.bufferSizeInFrames } returns 64000
            every { p.manager.startBluetoothSco() } throws SecurityException("SCO unavailable")
            val headset = p.addHeadset()
            val unrelated = AudioRoutingPlatform.device(77, AudioDeviceInfo.TYPE_USB_DEVICE, "usb", true)
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            repeat(5) { step ->
                if (step % 2 == 0) {
                    p.inputDevices = p.inputDevices + unrelated
                    p.changed(added = listOf(unrelated))
                } else {
                    p.inputDevices = p.inputDevices.filterNot { it.id == unrelated.id }
                    p.changed(removed = listOf(unrelated))
                }
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                p.capture(route, frames = 16000)
                assertFalse(route.takeReopenRequest())
            }
            assertTrue("Unrelated USB activity must not prevent Phone verification", p.state.receivingFallback)
        }
    }

    @Test fun healthyPhoneFallbackCanVerifyALargeClientBufferWithoutReopening() {
        AudioRoutingPlatform().use { p ->
            every { p.record.bufferSizeInFrames } returns 64000 // Four seconds, a supported runtime setting.
            every { p.manager.startBluetoothSco() } throws SecurityException("SCO unavailable")
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            repeat(4) {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                p.capture(route, frames = 16000)
                assertFalse("Matching positive PCM is verification progress, not a capture stall", route.takeReopenRequest())
                assertFalse(p.state.receivingFallback)
            }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            p.capture(route, frames = 16000)
            assertTrue(p.state.receivingFallback)
        }
    }

    @Test fun activatedHeadsetCanVerifyTheLargestSupportedBufferWithoutActivationTimeout() {
        AudioRoutingPlatform().use { p ->
            every { p.record.bufferSizeInFrames } returns 160000 // Ten seconds.
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(headset.source, headset.sink)
            repeat(10) {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                p.capture(route, frames = 16000)
                assertFalse(route.isFallback)
                assertTrue(p.state.connecting)
            }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            p.capture(route, frames = 16000)
            assertFalse(p.state.connecting)
            assertFalse(route.isFallback)
        }
    }

    @Test fun continuousTransitionsCannotExtendUnverifiedPhoneFallbackIndefinitely() {
        AudioRoutingPlatform().use { p ->
            every { p.record.bufferSizeInFrames } returns 16000
            every { p.manager.startBluetoothSco() } throws SecurityException("SCO unavailable")
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            var failed = false
            for (step in 1..80) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(100))
                try { route.beforeRead(p.record, false) }
                catch (_: IllegalStateException) { failed = true; break }
                p.observeRoute(p.phone) // Every read crosses another observed routing epoch.
                route.afterRead(p.record, 1600, false)
                assertFalse(p.state.receivingFallback)
            }
            assertTrue("Raw input preservation must not imply unbounded unverified capture", failed)
        }
    }

    @Test fun scoReadinessArrivingDuringInputInspectionDoesNotInventAModeMismatch() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started(); p.capture(route)
            var connectDuringInspection = true
            every { p.phone.type } answers {
                if (connectDuringInspection) {
                    connectDuringInspection = false
                    p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
                }
                AudioDeviceInfo.TYPE_BUILTIN_MIC
            }
            route.beforeRead(p.record, false)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, p.mode)
            assertFalse("An unsampled mode is not an Android rejection", route.isFallback)
            assertEquals(headset.source.id, p.preferred?.id)
        }
    }

    @Test @Config(sdk = [31, 36]) fun standardScoSelectsTheClassicPortOfTheChosenDualModeHeadset() {
        AudioRoutingPlatform().use { p ->
            val ble = p.addHeadset(10, 8, "same", AudioDeviceInfo.TYPE_BLE_HEADSET)
            val classic = p.addHeadset(17, 18, "same", AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            val route = p.route(ble.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED); p.capture(route)
            assertEquals(classic.source.id, p.preferred?.id)
            p.observeRoute(classic.source); p.capture(route)
            assertEquals(ble.choice.key, p.state.actual?.key)
            assertFalse(p.state.connecting)
            assertFalse(route.isFallback)
        }
    }

    @Test @Config(sdk = [31, 36]) fun scoCannotUseBleAudioAsIfItWereTheRequestedClassicTransport() {
        AudioRoutingPlatform().use { p ->
            val ble = p.addHeadset(10, 8, "ble-only", AudioDeviceInfo.TYPE_BLE_HEADSET)
            val route = p.route(ble.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(ble.source); p.capture(route)
            assertTrue(route.isFallback)
            verify(exactly = 0) { p.record.setPreferredDevice(ble.source) }
        }
    }

    @Test fun staleSelectedPortCannotActivateADifferentKnownDeviceReusingItsId() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset(address = "original")
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            p.inputDevices = listOf(p.phone, AudioRoutingPlatform.device(headset.source.id,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "replacement", true))
            p.communicationDevices = listOf(AudioRoutingPlatform.device(headset.sink.id,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "replacement"))
            // Inventory callbacks have not run since selection was frozen.
            route.attach(p.record); route.started(); p.capture(route)
            assertTrue(route.isFallback)
            verify(exactly = 0) { p.manager.startBluetoothSco() }
        }
    }

    @Test fun aChangedNativeAddressCannotHideBehindACachedInputId() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset(address = "original")
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(headset.source, headset.sink); p.capture(route)
            p.actualInput = AudioRoutingPlatform.device(headset.source.id,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "replacement", true)
            route.beforeRead(p.record, false)
            assertTrue(route.isFallback)
            assertNotEquals(headset.choice.key, p.state.actual?.key)
        }
    }

    @Test fun clientBufferWarmupDelaysConfirmationWithoutRejectingCapturedBlocks() {
        AudioRoutingPlatform().use { p ->
            every { p.record.bufferSizeInFrames } returns 1600
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(headset.source, headset.sink)
            repeat(2) { p.capture(route, frames = 800); assertTrue(p.state.connecting) }
            p.capture(route, frames = 800)
            assertFalse(p.state.connecting)
            assertFalse(route.isFallback)
        }
    }

    @Test fun standardScoHonorsNormalWithoutSubstitutingTheCommunicationApi() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD.copy(mode = BluetoothAudioMode.NORMAL))
            route.attach(p.record); route.started()
            verify(exactly = 1) { p.manager.startBluetoothSco() }
            verify(exactly = 0) { p.manager.mode = AudioManager.MODE_IN_COMMUNICATION }
            if (Build.VERSION.SDK_INT >= 31) verify(exactly = 0) { p.manager.setCommunicationDevice(any()) }
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(headset.source, headset.sink); p.capture(route)
            assertEquals(AudioManager.MODE_NORMAL, p.mode)
            assertFalse(p.state.connecting)
            assertNull(p.state.fallbackFrom)
            assertEquals(headset.source.id, p.preferred?.id)
            route.close()
            verify(exactly = 1) { p.manager.stopBluetoothSco() }
            if (Build.VERSION.SDK_INT >= 31) verify(exactly = 0) { p.manager.clearCommunicationDevice() }
        }
    }

    @Test @Config(sdk = [31, 36]) fun communicationDeviceIsAnExplicitChoiceAndHonorsBothModes() {
        for (mode in BluetoothAudioMode.entries) AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD.copy(route = communicationRoute(), mode = mode))
            route.attach(p.record); route.started()
            p.connect(headset); p.capture(route)
            assertEquals(if (mode == BluetoothAudioMode.NORMAL) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION, p.mode)
            assertFalse(p.state.connecting)
            assertNull(p.state.fallbackFrom)
            verify(exactly = 1) { p.manager.setCommunicationDevice(headset.sink) }
            verify(exactly = 0) { p.manager.startBluetoothSco() }
        }
    }

    @Test @Config(sdk = [28]) fun unsupportedCommunicationApiReportsFallbackWithoutTryingAnotherBluetoothApi() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset()
            val reports = mutableListOf<String>()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD.copy(route = communicationRoute()), reports::add)
            route.attach(p.record); route.started(); p.capture(route)
            assertTrue(route.isFallback)
            assertTrue(p.state.receivingFallback)
            assertEquals(headset.choice.key, p.controller().state.value!!.selected.key)
            assertTrue(reports.any { it.contains("Android 12") })
            verify(exactly = 0) { p.manager.startBluetoothSco() }
            verify(exactly = 0) { p.manager.mode = any() }
        }
    }

    @Test fun anIgnoredModeRequestCannotBeReportedAsSuccessfulHeadsetCapture() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset()
            val reports = mutableListOf<String>()
            p.mode = AudioManager.MODE_IN_COMMUNICATION
            every { p.manager.mode = AudioManager.MODE_NORMAL } answers { Unit }
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD.copy(mode = BluetoothAudioMode.NORMAL), reports::add)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(headset.source, headset.sink); p.capture(route)
            assertTrue("An accepted link is not proof of the requested mode", route.isFallback)
            assertTrue(reports.any { it.contains("mode", ignoreCase = true) && it.contains("NORMAL") })
            verify(atLeast = 1) { p.manager.mode = AudioManager.MODE_NORMAL }
        }
    }

    @Test fun aModeChangeIsDetectedWithoutWaitingForARoutingCallback() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(headset.source, headset.sink); p.capture(route)
            assertFalse(p.state.connecting)
            p.mode = AudioManager.MODE_NORMAL
            route.beforeRead(p.record, false)
            assertTrue(route.isFallback)
            assertEquals(p.phone.id, p.preferred?.id)
        }
    }

    @Test fun aChangedInputBeforeReadCannotUseCachedHeadsetSuccess() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset()
            val other = p.addHeadset(17, 18, "other")
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(headset.source, headset.sink); p.capture(route)
            p.actualInput = other.source // Android's callback is still queued.
            route.beforeRead(p.record, false)
            assertTrue(route.isFallback)
            assertEquals(p.phone.id, p.preferred?.id)
        }
    }

    @Test fun aChangeDuringReadPreservesTheReturnedBlockButDoesNotConfirmFallbackAudio() {
        AudioRoutingPlatform().use { p ->
            val headset = p.addHeadset()
            val route = p.route(headset.choice.key, MicrophoneOptions.STANDARD)
            route.attach(p.record); route.started()
            p.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            p.observeRoute(headset.source, headset.sink); p.capture(route)
            route.beforeRead(p.record, false)
            p.actualInput = p.phone
            // afterRead must return normally so its already-captured PCM reaches
            // the writer. A crossed boundary is not verified Phone continuation.
            route.afterRead(p.record, 800, false)
            assertTrue(route.isFallback)
            assertFalse(p.state.receivingFallback)
            p.capture(route)
            assertTrue(p.state.receivingFallback)
        }
    }

    @Test fun anExplicitPhoneSelectionCannotSilentlyContinueOnAnotherMicrophone() {
        AudioRoutingPlatform().use { p ->
            val other = p.addHeadset()
            val route = p.route()
            route.attach(p.record); route.started(); p.capture(route)
            p.actualInput = other.source
            assertThrows(IllegalStateException::class.java) { route.beforeRead(p.record, false) }
        }
    }

    private fun communicationRoute(): BluetoothCaptureRoute {
        val route = BluetoothCaptureRoute.entries.singleOrNull { it.name == "COMMUNICATION_DEVICE" }
        assertNotNull("Communication-device routing must be an explicit choice", route)
        return route!!
    }
}
