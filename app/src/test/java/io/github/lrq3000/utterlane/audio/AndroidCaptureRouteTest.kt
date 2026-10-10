package io.github.lrq3000.utterlane.audio

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 36])
class AndroidCaptureRouteTest {
    @Test fun requestAcceptanceWaitsForActualHeadsetAndUnsilencedFrames() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started()
            assertNull("Startup must reach the Bluetooth request without a caught platform error",
                org.robolectric.shadows.ShadowLog.getLogsForTag("CaptureRoute").lastOrNull { it.throwable != null }?.throwable?.stackTraceToString())
            platform.capture(route)
            assertTrue(platform.state.actual!!.isPhone)
            assertTrue(platform.state.connecting)
            platform.connect(headset)
            platform.capture(route, silenced = true)
            assertTrue("Routing alone is not successful audio capture", platform.state.connecting)
            platform.capture(route)
            assertFalse(platform.state.connecting)
            assertEquals(headset.choice.key, platform.state.actual!!.key)
            assertNull(platform.state.fallbackFrom)
            assertEquals(headset.source.id, platform.preferred!!.id)
            assertAcquired(platform, headset)
            route.close(); route.close()
            assertReleased(platform, 1)
        }
    }

    @Test fun delayedActivationKeepsPhoneCaptureUntilTheInitialHeadsetArrives() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            platform.capture(route)
            assertTrue(platform.state.actual!!.isPhone)
            assertTrue(platform.state.connecting)
            platform.connect(headset)
            platform.capture(route)
            assertEquals(headset.choice.key, platform.state.actual!!.key)
            assertFalse(platform.state.connecting)
        }
    }

    @Test fun activationTimeoutKeepsRecordingOnPhoneAndReleasesTheRequest() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started()
            platform.capture(route)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            platform.capture(route)
            assertEquals(InputFallbackReason.UNAVAILABLE, platform.state.fallbackReason)
            assertTrue(platform.state.receivingFallback)
            assertReleased(platform, 1)
        }
    }

    @Test fun rejectedOrThrowingActivationFallsBackWithoutClaimingHeadsetCapture() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            if (Build.VERSION.SDK_INT >= 31) platform.acceptCommunication = false
            else every { platform.manager.startBluetoothSco() } throws SecurityException("Denied SCO request")
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started()
            platform.capture(route)
            assertTrue(platform.state.receivingFallback)
            assertTrue(platform.state.actual!!.isPhone)
            assertEquals(AudioManager.MODE_NORMAL, platform.mode)
            if (Build.VERSION.SDK_INT >= 31) verify(exactly = 0) { platform.manager.clearCommunicationDevice() }
            else verify(exactly = 1) { platform.manager.stopBluetoothSco() }
        }
    }

    @Test fun stopDuringModeAcquisitionCannotStartBluetoothAfterStop() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            every { platform.manager.mode = AudioManager.MODE_IN_COMMUNICATION } answers {
                platform.mode = AudioManager.MODE_IN_COMMUNICATION; platform.running = false
            }
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started(); route.close()
            assertEquals(AudioManager.MODE_NORMAL, platform.mode)
            verify(exactly = 0) { platform.manager.startBluetoothSco() }
            if (Build.VERSION.SDK_INT >= 31) verify(exactly = 0) { platform.manager.setCommunicationDevice(any()) }
        }
    }

    @Test fun activeHeadsetLossFallsBackEvenWhileAnotherHeadsetRemains() = runBlocking {
        AudioRoutingPlatform().use { platform ->
            val first = platform.addHeadset()
            val second = platform.addHeadset(17, 18, "other")
            val route = platform.route(first.choice.key)
            platform.controller().setPreferBluetooth(true)
            route.attach(platform.record); route.started()
            platform.connect(first); platform.capture(route)
            platform.inputDevices = listOf(platform.phone, second.source)
            platform.communicationDevices = listOf(second.sink)
            platform.changed(removed = listOf(first.source, first.sink))
            platform.observeRoute(platform.phone, null)
            platform.capture(route)
            assertTrue(platform.state.receivingFallback)
            assertEquals(first.choice.key, platform.state.fallbackFrom!!.key)
            assertEquals(second.choice.key, platform.controller().state.value!!.selected.key)
            platform.inputDevices = platform.inputDevices + first.source
            platform.communicationDevices = platform.communicationDevices + first.sink
            platform.changed(added = listOf(first.source, first.sink))
            platform.capture(route)
            assertTrue(platform.state.actual!!.isPhone)
            assertTrue(platform.state.receivingFallback)
            assertAcquired(platform, first)
        }
    }

    @Test fun rapidReconnectCannotHideActiveInputLossBetweenCaptureCycles() = runBlocking {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            val other = platform.addHeadset(17, 18, "other")
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started(); platform.connect(headset); platform.capture(route)
            // Next-session preferences now watch a different headset. Active
            // capture must independently remember losing its original connection.
            platform.controller().select(other.choice.key)
            platform.inputDevices = listOf(platform.phone, other.source)
            platform.communicationDevices = listOf(other.sink)
            platform.changed(removed = listOf(headset.source, headset.sink))
            platform.inputDevices = platform.inputDevices + headset.source
            platform.communicationDevices = platform.communicationDevices + headset.sink
            platform.changed(added = listOf(headset.source, headset.sink))
            platform.capture(route)
            assertTrue("A removal cannot be erased by a later connected snapshot", route.isFallback)
            assertEquals(InputFallbackReason.DISCONNECTED, platform.state.fallbackReason)
            assertEquals(platform.phone.id, platform.preferred!!.id)
            platform.observeRoute(platform.phone, null)
            platform.capture(route)
            assertTrue(platform.state.receivingFallback)
            assertEquals(other.choice.key, platform.controller().state.value!!.selected.key)
        }
    }

    @Test fun existingCallModeIsNeitherAcquiredNorClearedByFallback() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            platform.mode = AudioManager.MODE_IN_CALL
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started(); platform.capture(route); route.close()
            assertTrue(platform.state.receivingFallback)
            assertEquals(AudioManager.MODE_IN_CALL, platform.mode)
            assertNoCommunicationRequests(platform)
        }
    }

    @Test fun externalCommunicationModeIsPreservedWhenOurDeviceRequestEnds() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            platform.mode = AudioManager.MODE_IN_COMMUNICATION
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started()
            platform.connect(headset); platform.capture(route); route.close()
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.mode)
            verify(exactly = 0) { platform.manager.mode = any() }
            assertReleased(platform, 1)
        }
    }

    @Test fun staleRecorderCallbackDoesNotAffectTheNextSession() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            val old = platform.route(headset.choice.key)
            old.attach(platform.record); old.started(); platform.connect(headset); platform.capture(old)
            val callback = platform.queuedRoutingCallback()
            old.detach(platform.record); old.close()
            platform.observeRoute(platform.phone, null)
            val next = platform.route()
            next.attach(platform.record); next.started()
            callback(); shadowOf(Looper.getMainLooper()).idle()
            platform.capture(next)
            assertTrue(platform.state.actual!!.isPhone)
            assertNull(platform.state.fallbackFrom)
            assertAcquired(platform, headset)
            assertReleased(platform, 1)
        }
    }

    @Test @Config(sdk = [31, 36]) fun partialCommunicationRequestFailureStillReleasesItsOwnership() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            every { platform.manager.setCommunicationDevice(any()) } answers {
                platform.currentCommunication = firstArg()
                throw IllegalStateException("Simulated failure after service accepted request")
            }
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started(); platform.capture(route); route.close()
            assertTrue(platform.state.receivingFallback)
            verify(exactly = 1) { platform.manager.clearCommunicationDevice() }
            assertEquals(AudioManager.MODE_NORMAL, platform.mode)
        }
    }

    @Test fun partialModeAcquisitionFailureAlsoReleasesTheModeClaim() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            every { platform.manager.mode = AudioManager.MODE_IN_COMMUNICATION } answers {
                platform.mode = AudioManager.MODE_IN_COMMUNICATION
                throw IllegalStateException("Simulated failure after mode acquisition")
            }
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started(); platform.capture(route); route.close()
            assertTrue(platform.state.receivingFallback)
            assertEquals(AudioManager.MODE_NORMAL, platform.mode)
        }
    }

    @Test @Config(sdk = [31, 36]) fun bleSourceAppearingAfterActivationIsConfirmedFromFreshInventory() {
        AudioRoutingPlatform().use { platform ->
            val sink = AudioRoutingPlatform.device(8, AudioDeviceInfo.TYPE_BLE_HEADSET, "headset")
            platform.communicationDevices = listOf(sink)
            val choice = platform.devices.inputs().single { it.bluetooth }
            assertNull(choice.inputId)
            val route = platform.route(choice.key)
            route.attach(platform.record); route.started()
            val source = AudioRoutingPlatform.device(7, AudioDeviceInfo.TYPE_BLE_HEADSET, "", true)
            platform.inputDevices = listOf(platform.phone, source)
            platform.observeRoute(source, sink)
            platform.capture(route)
            assertFalse("Native routing confirmation must not wait for the settings refresh coroutine", platform.state.connecting)
            platform.changed(added = listOf(source))
            platform.capture(route)
            assertEquals(choice.key, platform.state.actual!!.key)
            assertFalse(platform.state.connecting)
            assertNull(platform.state.fallbackFrom)
        }
    }

    @Test @Config(sdk = [31, 36]) fun activeSourceSurvivesAnUnrelatedAmbiguousCatalogueChange() {
        AudioRoutingPlatform().use { platform ->
            val first = platform.addHeadset(address = "same")
            val route = platform.route(first.choice.key)
            route.attach(platform.record); route.started(); platform.connect(first); platform.capture(route)
            val other = platform.addHeadset(17, 18, "same")
            platform.changed(added = listOf(other.source, other.sink))
            platform.capture(route)
            assertFalse("Unrelated metadata ambiguity is not loss of an already-bound source", route.isFallback)
            assertEquals(first.choice.key, platform.state.actual!!.key)
            assertEquals(first.source.id, platform.preferred!!.id)
        }
    }

    @Test @Config(sdk = [31, 36]) fun removedSourceBindingCannotMisidentifyAReusedInputPort() {
        AudioRoutingPlatform().use { platform ->
            val first = platform.addHeadset()
            val route = platform.route(first.choice.key)
            route.attach(platform.record); route.started(); platform.connect(first); platform.capture(route)
            val reusedSource = AudioRoutingPlatform.device(first.source.id, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "other", true)
            val otherSink = AudioRoutingPlatform.device(18, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "other")
            platform.inputDevices = listOf(platform.phone, reusedSource)
            platform.communicationDevices = listOf(first.sink, otherSink)
            platform.changed(removed = listOf(first.source), added = listOf(reusedSource, otherSink))
            platform.observeRoute(reusedSource, otherSink)
            platform.capture(route)
            assertTrue(route.isFallback)
            assertEquals(InputFallbackReason.ROUTE_CHANGED, platform.state.fallbackReason)
        }
    }

    @Test @Config(sdk = [31, 36]) fun removalDuringRouteInspectionCannotRebindFromStaleInventory() {
        AudioRoutingPlatform().use { platform ->
            val first = platform.addHeadset(address = "same")
            val route = platform.route(first.choice.key)
            route.attach(platform.record); route.started(); platform.connect(first); platform.capture(route)
            var removeDuringQuery = true
            every { platform.record.routedDevice } answers {
                if (removeDuringQuery) {
                    removeDuringQuery = false
                    val replacement = AudioRoutingPlatform.device(first.source.id, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "same", true)
                    val otherSink = AudioRoutingPlatform.device(18, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "same")
                    platform.inputDevices = listOf(platform.phone, replacement)
                    platform.communicationDevices = listOf(first.sink, otherSink)
                    platform.changed(removed = listOf(first.source), added = listOf(replacement, otherSink))
                    platform.observeRoute(replacement, otherSink)
                }
                platform.actualInput
            }
            platform.observeRoute(first.source, first.sink)
            platform.capture(route)
            platform.capture(route)
            assertNotEquals(first.choice.key, platform.state.actual?.key)
            assertTrue("Old inventory cannot erase a concurrent source-removal signal", route.isFallback)
        }
    }

    @Test @Config(sdk = [31, 36]) fun disprovedBindingCannotReturnWhenCatalogueBecomesAmbiguous() {
        AudioRoutingPlatform().use { platform ->
            val source = AudioRoutingPlatform.device(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "B", true)
            val candidateA = AudioRoutingPlatform.device(8, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "")
            platform.inputDevices = listOf(platform.phone, source)
            platform.communicationDevices = listOf(candidateA)
            val selected = platform.devices.inputs().single { it.bluetooth }
            val route = platform.route(selected.key)
            route.attach(platform.record); route.started(); platform.capture(route)
            val candidateB = AudioRoutingPlatform.device(18, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "B")
            platform.communicationDevices = listOf(candidateA, candidateB)
            platform.changed(added = listOf(candidateB))
            platform.capture(route) // Still Phone; exact metadata disproves the old inferred A binding.
            val otherSource = AudioRoutingPlatform.device(17, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "B", true)
            platform.inputDevices = platform.inputDevices + otherSource
            platform.changed(added = listOf(otherSource))
            platform.observeRoute(source, candidateA)
            platform.capture(route)
            assertNotEquals(selected.key, platform.state.actual?.key)
            assertTrue("Unknown evidence cannot resurrect a positively disproved association", platform.state.connecting)
        }
    }

    @Test fun failedReleaseGetsOneFinalRetryWithoutPerFrameRetrySpam() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            var attempts = 0
            if (Build.VERSION.SDK_INT >= 31) {
                every { platform.manager.clearCommunicationDevice() } answers {
                    if (++attempts == 1) throw IllegalStateException("Transient release failure")
                }
            } else {
                every { platform.manager.stopBluetoothSco() } answers {
                    if (++attempts == 1) throw IllegalStateException("Transient release failure")
                }
            }
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started(); platform.connect(headset); platform.capture(route)
            platform.inputDevices = listOf(platform.phone); platform.communicationDevices = emptyList()
            platform.changed(removed = listOf(headset.source, headset.sink))
            platform.observeRoute(platform.phone, null)
            repeat(10) { platform.capture(route) }
            assertEquals("Do not retry cleanup on every PCM block", 1, attempts)
            route.close(); route.close()
            assertEquals("Close makes one final attempt to release owned resources", 2, attempts)
        }
    }

    @Test @Config(sdk = [28]) fun legacyScoRoutingExceptionContinuesPhoneCapture() {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset()
            every { platform.manager.isBluetoothScoOn = true } throws SecurityException("SCO routing denied")
            val route = platform.route(headset.choice.key)
            route.attach(platform.record); route.started()
            platform.scoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
            platform.capture(route)
            platform.capture(route)
            assertTrue(platform.state.receivingFallback)
            assertEquals(InputFallbackReason.UNAVAILABLE, platform.state.fallbackReason)
        }
    }

    @Test fun phoneCaptureNeverAcquiresOrClearsBluetoothRouting() {
        AudioRoutingPlatform().use { platform ->
            val route = platform.route()
            route.attach(platform.record)
            route.started()
            route.beforeRead(platform.record, false)
            route.afterRead(platform.record, 800, false)
            assertTrue(platform.state.actual!!.isPhone)
            assertNull(platform.state.fallbackFrom)
            route.close(); route.close()
            assertNoCommunicationRequests(platform)
        }
    }

    @Test fun stopBeforeActivationNeverAcquiresCommunicationMode() {
        AudioRoutingPlatform().use { platform ->
            val input = AudioRoutingPlatform.device(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "headset", true)
            val output = AudioRoutingPlatform.device(8, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "headset")
            platform.inputDevices = listOf(platform.phone, input)
            platform.communicationDevices = listOf(output)
            val headset = platform.devices.inputs().single { it.bluetooth }
            val route = platform.route(headset.key)
            route.attach(platform.record)
            platform.running = false
            route.started()
            route.close()
            assertNoCommunicationRequests(platform)
        }
    }

    private fun assertNoCommunicationRequests(platform: AudioRoutingPlatform) {
        verify(exactly = 0) { platform.manager.mode = any() }
        verify(exactly = 0) { platform.manager.startBluetoothSco() }
        verify(exactly = 0) { platform.manager.stopBluetoothSco() }
        if (Build.VERSION.SDK_INT >= 31) {
            verify(exactly = 0) { platform.manager.setCommunicationDevice(any()) }
            verify(exactly = 0) { platform.manager.clearCommunicationDevice() }
        }
    }

    private fun assertAcquired(platform: AudioRoutingPlatform, headset: AudioRoutingPlatform.Headset) {
        if (Build.VERSION.SDK_INT >= 31) verify(exactly = 1) { platform.manager.setCommunicationDevice(headset.sink) }
        else verify(exactly = 1) { platform.manager.startBluetoothSco() }
    }

    private fun assertReleased(platform: AudioRoutingPlatform, count: Int) {
        if (Build.VERSION.SDK_INT >= 31) verify(exactly = count) { platform.manager.clearCommunicationDevice() }
        else verify(exactly = count) { platform.manager.stopBluetoothSco() }
    }
}
