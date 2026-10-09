package io.github.lrq3000.utterlane.audio

import android.Manifest
import android.app.Application
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 36])
class HfpCaptureIntegrationTest {
    private class BluetoothContext(base: Context) : ContextWrapper(base) {
        val bluetooth = mockk<BluetoothManager>()
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val proxy = mockk<BluetoothHeadset>(relaxed = true)
        val peer = mockk<BluetoothDevice>(relaxed = true)
        val listener = slot<BluetoothProfile.ServiceListener>()
        var granted = true
        var receiver: BroadcastReceiver? = null
        init {
            every { bluetooth.adapter } returns adapter
            every { adapter.isEnabled } returns true
            every { adapter.getProfileProxy(any(), capture(listener), BluetoothProfile.HEADSET) } returns true
            every { peer.address } returns ADDRESS
            every { peer.name } returns "Headset"
            every { proxy.connectedDevices } returns listOf(peer)
            every { proxy.getConnectionState(peer) } returns BluetoothProfile.STATE_CONNECTED
            every { proxy.startVoiceRecognition(peer) } returns true
        }
        override fun getSystemService(name: String): Any? = if (name == Context.BLUETOOTH_SERVICE) bluetooth else super.getSystemService(name)
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.BLUETOOTH_CONNECT) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            } else super.checkPermission(permission, pid, uid)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, permission: String?, handler: Handler?, flags: Int): Intent? {
            this.receiver = receiver; return null
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) { this.receiver = null }
        fun connected() {
            every { proxy.isAudioConnected(peer) } returns true
            receiver?.onReceive(this, Intent(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, peer).putExtra(BluetoothProfile.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_CONNECTED))
        }
    }

    @Test fun hfpUsesExplicitProfileNormalModeAndActualInputRatherThanStandardSco() = runBlocking {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset(address = ADDRESS)
            val context = BluetoothContext(platform.context)
            platform.controller().select(headset.choice.key)
            val route = AndroidCaptureRoute(context, platform.controller(), platform.controller().snapshotForRecording(),
                { platform.running }, { platform.state = it }, MicrophoneOptions.HFP)
            try {
                route.attach(platform.record); route.started()
                assertTrue("HFP preset must request the headset profile", context.listener.isCaptured)
                context.listener.captured.onServiceConnected(BluetoothProfile.HEADSET, context.proxy)
                platform.capture(route)
                assertTrue(platform.state.actual!!.isPhone)
                assertTrue(platform.state.connecting)
                context.connected()
                platform.observeRoute(headset.source)
                platform.capture(route)
                assertEquals(headset.choice.key, platform.state.actual!!.key)
                assertFalse(platform.state.connecting)
                assertEquals(AudioManager.MODE_NORMAL, platform.mode)
                verify(exactly = 0) { platform.manager.startBluetoothSco() }
                if (Build.VERSION.SDK_INT >= 31) verify(exactly = 0) { platform.manager.setCommunicationDevice(any()) }
                verify(exactly = 1) { context.proxy.startVoiceRecognition(context.peer) }
                platform.inputDevices = listOf(platform.phone); platform.communicationDevices = emptyList()
                platform.changed(removed = listOf(headset.source, headset.sink))
                platform.observeRoute(platform.phone, null); platform.capture(route)
                assertTrue(platform.state.receivingFallback)
            } finally { route.detach(platform.record); route.close() }
            verify(exactly = 1) { context.proxy.stopVoiceRecognition(context.peer) }
            verify(exactly = 1) { context.adapter.closeProfileProxy(BluetoothProfile.HEADSET, context.proxy) }
        }
    }

    @Test @Config(sdk = [31, 36]) fun missingNearbyPermissionKeepsPhoneCaptureAndNeverSubstitutesStandardRoute() = runBlocking {
        AudioRoutingPlatform().use { platform ->
            val headset = platform.addHeadset(address = ADDRESS)
            val context = BluetoothContext(platform.context).apply { granted = false }
            platform.controller().select(headset.choice.key)
            val route = AndroidCaptureRoute(context, platform.controller(), platform.controller().snapshotForRecording(),
                { platform.running }, { platform.state = it }, MicrophoneOptions.HFP)
            try {
                route.attach(platform.record); route.started(); platform.capture(route)
                assertTrue(platform.state.receivingFallback)
                verify(exactly = 0) { context.adapter.getProfileProxy(any(), any(), any()) }
                verify(exactly = 0) { platform.manager.setCommunicationDevice(any()) }
            } finally { route.detach(platform.record); route.close() }
        }
    }

    @Test @Config(sdk = [31, 36]) fun hfpCannotRelabelAnInputAfterAnInferredTargetIsDisproved() = runBlocking {
        AudioRoutingPlatform().use { platform ->
            val source = AudioRoutingPlatform.device(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, ADDRESS, true)
            val target = AudioRoutingPlatform.device(8, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "")
            platform.inputDevices = listOf(platform.phone, source)
            platform.communicationDevices = listOf(target)
            val choice = platform.devices.inputs().single { it.bluetooth }
            val context = BluetoothContext(platform.context)
            platform.controller().select(choice.key)
            val route = AndroidCaptureRoute(context, platform.controller(), platform.controller().snapshotForRecording(),
                { platform.running }, { platform.state = it }, MicrophoneOptions.HFP)
            try {
                route.attach(platform.record); route.started()
                context.listener.captured.onServiceConnected(BluetoothProfile.HEADSET, context.proxy)
                context.connected(); platform.capture(route)
                val other = AudioRoutingPlatform.device(18, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, ADDRESS)
                platform.communicationDevices = listOf(target, other)
                platform.changed(added = listOf(other))
                platform.capture(route) // Actual input is still Phone at this point.
                assertTrue("Positive contrary endpoint evidence invalidates an inferred HFP association", route.isFallback)
                platform.observeRoute(source)
                platform.capture(route)
                assertNotEquals(choice.key, platform.state.actual?.key)
            } finally { route.detach(platform.record); route.close() }
        }
    }

    @Test @Config(sdk = [31, 36]) fun aMatchedDualModeHeadsetPrefersItsClassicInputForHfp() = runBlocking {
        AudioRoutingPlatform().use { platform ->
            val ble = platform.addHeadset(10, 8, ADDRESS, AudioDeviceInfo.TYPE_BLE_HEADSET)
            val classic = platform.addHeadset(17, 18, ADDRESS, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            val context = BluetoothContext(platform.context)
            platform.controller().select(ble.choice.key)
            val route = AndroidCaptureRoute(context, platform.controller(), platform.controller().snapshotForRecording(),
                { platform.running }, { platform.state = it }, MicrophoneOptions.HFP)
            try {
                route.attach(platform.record); route.started()
                context.listener.captured.onServiceConnected(BluetoothProfile.HEADSET, context.proxy)
                context.connected(); platform.capture(route)
                assertEquals("HFP must not pin the BLE input that its verifier rejects", classic.source.id, platform.preferred?.id)
                platform.observeRoute(classic.source); platform.capture(route)
                assertEquals(ble.choice.key, platform.state.actual?.key)
                assertFalse(platform.state.connecting)
                assertFalse(route.isFallback)
            } finally { route.detach(platform.record); route.close() }
        }
    }

    companion object { private const val ADDRESS = "00:11:22:33:44:55" }
}
