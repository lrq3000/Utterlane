package io.github.lrq3000.utterlane.audio

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 36])
class AndroidCaptureRouteTest {
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
}
