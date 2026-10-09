package io.github.lrq3000.utterlane.audio

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercise the real transport against deterministic Binder/API boundaries, without a radio. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 36])
class HfpMicrophoneRouteTest {
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val bluetooth = mockk<BluetoothManager>()
    private val audio = mockk<AudioManager>(relaxed = true)
    private val proxy = mockk<BluetoothHeadset>(relaxed = true)
    private val listener = slot<BluetoothProfile.ServiceListener>()
    private lateinit var peer: BluetoothDevice
    private lateinit var target: AudioDeviceInfo
    private lateinit var context: TestContext
    private lateinit var route: HfpMicrophoneRoute
    @Volatile private var running = true
    private var now = 0L
    private var signals = 0

    private inner class TestContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var permission = true
        var receiver: BroadcastReceiver? = null
        var filter: IntentFilter? = null
        var receiverFlags: Int? = null
        var unregisters = 0
        var registrationFailure = false
        var unregisterFailure = false
        var onPermissionCheck: (() -> Unit)? = null

        override fun getSystemService(name: String): Any? = when (name) {
            Context.BLUETOOTH_SERVICE -> bluetooth
            Context.AUDIO_SERVICE -> audio
            else -> super.getSystemService(name)
        }

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.BLUETOOTH_CONNECT) {
                onPermissionCheck?.invoke()
                if (this.permission) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            } else super.checkPermission(permission, pid, uid)

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?,
            broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? {
            this.receiver = receiver
            this.filter = filter
            receiverFlags = flags
            if (registrationFailure) throw IllegalStateException("partially registered")
            return null
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            assertSame(this.receiver, receiver)
            unregisters++
            if (unregisterFailure) throw IllegalStateException("unregister failed")
        }
    }

    @Before fun setUp() {
        context = TestContext()
        peer = headset(ADDRESS)
        target = input(1, ADDRESS)
        every { bluetooth.adapter } returns adapter
        every { adapter.isEnabled } returns true
        every { adapter.getProfileProxy(any(), capture(listener), BluetoothProfile.HEADSET) } returns true
        every { proxy.connectedDevices } returns listOf(peer)
        every { proxy.getConnectionState(peer) } returns BluetoothProfile.STATE_CONNECTED
        every { proxy.startVoiceRecognition(peer) } returns true
        route = newRoute()
    }

    @After fun tearDown() {
        route.close()
        // Mode/SCO routing ownership is exclusively the parent's responsibility on every SDK.
        verify { audio wasNot Called }
    }

    private fun newRoute(selected: AudioDeviceInfo? = target) = HfpMicrophoneRoute(
        context, selected, { running }, { signals++ }, { now }
    )

    private fun headset(address: String) = mockk<BluetoothDevice>(relaxed = true).also {
        every { it.address } returns address
        every { it.name } returns "Same headset name"
    }

    private fun input(id: Int, address: String = "", type: Int = AudioDeviceInfo.TYPE_BLUETOOTH_SCO) =
        mockk<AudioDeviceInfo>(relaxed = true).also {
            every { it.id } returns id
            every { it.address } returns address
            every { it.type } returns type
            every { it.isSource } returns true
            every { it.productName } returns "Same headset name"
        }

    private fun acquire() {
        route.start()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        route.poll()
    }

    private fun ready() {
        every { proxy.isAudioConnected(peer) } returns true
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.READY, route.status.phase)
    }

    private fun event(action: String, state: Int, device: BluetoothDevice = peer) {
        context.receiver!!.onReceive(context, Intent(action)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            .putExtra(BluetoothProfile.EXTRA_STATE, state))
    }

    @Test fun permissionGateIsModernOnlyAndNeverRequestsAudio() {
        context.permission = false
        route.start()
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
            assertTrue(route.status.permissionRequired)
            verify(exactly = 0) { adapter.getProfileProxy(any(), any(), any()) }
            assertNull(context.receiver)
        } else {
            assertEquals(HfpMicrophoneRoute.Phase.CONNECTING, route.status.phase)
            verify(exactly = 1) { adapter.getProfileProxy(any(), any(), BluetoothProfile.HEADSET) }
        }
    }

    @Test fun absentOrDisabledAdapterFailsWithoutRequestingProxy() {
        every { bluetooth.adapter } returns null
        route.start()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        route = newRoute()
        every { bluetooth.adapter } returns adapter
        every { adapter.isEnabled } returns false
        route.start()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 0) { adapter.getProfileProxy(any(), any(), any()) }
    }

    @Test fun callbackOnlySignalsAndAcceptanceIsNotReadiness() {
        assertEquals(HfpMicrophoneRoute.Phase.IDLE, route.status.phase)
        route.start()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        assertTrue(signals > 0)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        route.poll()
        assertEquals(true, route.status.requestAccepted)
        assertEquals(HfpMicrophoneRoute.Phase.CONNECTING, route.status.phase)
        // ContextCompat expresses exported as the pre-33 default (flags=0),
        // and forwards the explicit platform flag on Android 13 and newer.
        assertEquals(if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0, context.receiverFlags)
        assertTrue(context.filter!!.hasAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED))
        assertTrue(context.filter!!.hasAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED))
        event(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED, BluetoothHeadset.STATE_AUDIO_CONNECTED)
        route.poll()
        assertEquals("A stale CONNECTED broadcast is not evidence", HfpMicrophoneRoute.Phase.CONNECTING, route.status.phase)
        every { proxy.isAudioConnected(peer) } returns true
        event(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED, BluetoothHeadset.STATE_AUDIO_CONNECTED)
        assertEquals(HfpMicrophoneRoute.Phase.CONNECTING, route.status.phase)
        assertEquals(HfpMicrophoneRoute.Phase.READY, route.poll().phase)
        repeat(50) { route.start(); route.poll(); listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy) }
        verify(exactly = 1) { proxy.startVoiceRecognition(peer) }
        verify(exactly = 1) { proxy.connectedDevices }
        verify(exactly = 3) { proxy.isAudioConnected(peer) }
        route.close(); route.close()
        assertEquals(HfpMicrophoneRoute.Phase.CLOSED, route.status.phase)
        verify(exactly = 1) { proxy.stopVoiceRecognition(peer) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        assertEquals(1, context.unregisters)
    }

    @Test fun alreadyConnectedAudioCanBecomeReadyImmediatelyOnWorker() {
        ready()
        assertEquals(true, route.status.requestAccepted)
    }

    @Test fun rejectedAudioIsNotOwnedButProfileAndReceiverAreReleased() {
        every { proxy.startVoiceRecognition(peer) } returns false
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        assertEquals(false, route.status.requestAccepted)
        val failure = route.status
        route.close()
        assertEquals(failure.detail, route.status.detail)
        assertTrue(route.status.detail.contains("rejected", ignoreCase = true))
        verify(exactly = 0) { proxy.stopVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        assertEquals(1, context.unregisters)
    }

    @Test fun proxyRejectionAndWrongProfileAreTerminal() {
        every { adapter.getProfileProxy(any(), capture(listener), BluetoothProfile.HEADSET) } returns false
        route.start()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        every { adapter.getProfileProxy(any(), capture(listener), BluetoothProfile.HEADSET) } returns true
        route = newRoute()
        route.start()
        val wrong = mockk<BluetoothProfile>()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, wrong)
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.poll().phase)
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, wrong) }
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
    }

    @Test fun exactMismatchNeverSubstitutesSameNamedPeer() {
        route = newRoute(input(2, OTHER_ADDRESS))
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        assertFalse(route.status.detail.contains(OTHER_ADDRESS))
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
    }

    @Test fun exactMatchSelectsCorrectPeerAmongSeveral() {
        val other = headset(OTHER_ADDRESS)
        every { proxy.connectedDevices } returns listOf(other, peer)
        acquire()
        verify(exactly = 1) { proxy.startVoiceRecognition(peer) }
        verify(exactly = 0) { proxy.startVoiceRecognition(other) }
    }

    @Test fun noConnectedProfileFailsAndReleasesProxy() {
        every { proxy.connectedDevices } returns emptyList()
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun addresslessSelectionRequiresSoleClassicPeer() {
        route = newRoute(input(2))
        acquire()
        assertEquals(true, route.status.requestAccepted)
        route.close()
        route = newRoute(input(2))
        every { proxy.connectedDevices } returns listOf(peer, headset(OTHER_ADDRESS))
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 1) { proxy.startVoiceRecognition(any()) }
    }

    @Test fun addresslessBleSelectionCannotBorrowUnrelatedClassicPeer() {
        route = newRoute(input(2, type = AudioDeviceInfo.TYPE_BLE_HEADSET))
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
    }

    @Test fun timeoutIncludesProxyAcquisitionAndRejectsLateArrival() {
        route.start()
        now = 7_999
        assertEquals(HfpMicrophoneRoute.Phase.CONNECTING, route.poll().phase)
        now = 8_000
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.poll().phase)
        assertTrue(route.status.detail.contains("Timed out"))
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        route.poll()
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun timeoutAfterAcceptanceReleasesOwnedAudio() {
        acquire()
        now = 8_000
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.poll().phase)
        verify(exactly = 1) { proxy.stopVoiceRecognition(peer) }
    }

    @Test fun delayedWorkerCannotStartAudioAfterDeadline() {
        route.start()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        now = 8_000
        route.poll()
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun lateProxyAfterCloseIsReleasedDirectlyWithoutWorker() {
        route.start()
        route.close()
        repeat(2) { listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy) }
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        route.start()
        assertEquals(HfpMicrophoneRoute.Phase.CLOSED, route.poll().phase)
    }

    @Test fun cancellationBeforeStartOrDuringDeviceLookupCannotOpenAudio() {
        running = false
        route.start()
        verify(exactly = 0) { adapter.getProfileProxy(any(), any(), any()) }
        route = newRoute()
        running = true
        every { proxy.connectedDevices } answers { running = false; listOf(peer) }
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.CLOSED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun partialVoiceRequestFailureStillStopsOwnedAudioAndRedactsException() {
        every { proxy.startVoiceRecognition(peer) } throws SecurityException("Private address $ADDRESS")
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        assertTrue(route.status.permissionRequired)
        assertNull(route.status.requestAccepted)
        assertFalse(route.status.toString().contains(ADDRESS))
        verify(exactly = 1) { proxy.stopVoiceRecognition(peer) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun partiallyRegisteredReceiverIsUnregisteredOnFailure() {
        context.registrationFailure = true
        route.start()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        assertEquals(1, context.unregisters)
        verify(exactly = 0) { adapter.getProfileProxy(any(), any(), any()) }
    }

    @Test fun cleanupFailuresDoNotPreventOtherReleasesOrEraseFailure() {
        ready()
        every { proxy.stopVoiceRecognition(peer) } throws SecurityException("revoked")
        every { adapter.closeProfileProxy(any(), any()) } throws IllegalStateException("close failed")
        context.unregisterFailure = true
        listener.captured.onServiceDisconnected(BluetoothProfile.HEADSET)
        assertEquals(HfpMicrophoneRoute.Phase.READY, route.status.phase)
        route.poll()
        val failure = route.status.detail
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        route.close(); route.close()
        assertEquals(failure, route.status.detail)
        verify(exactly = 1) { proxy.stopVoiceRecognition(peer) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        assertEquals(1, context.unregisters)
    }

    @Test fun selectedPeerDisconnectIsStickyAndOtherPeerCannotDeclareReadinessOrLoss() {
        ready()
        val other = headset(OTHER_ADDRESS)
        event(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED, BluetoothHeadset.STATE_AUDIO_DISCONNECTED, other)
        assertEquals(HfpMicrophoneRoute.Phase.READY, route.poll().phase)
        event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_DISCONNECTED)
        event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED)
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.poll().phase)
        verify(exactly = 1) { proxy.stopVoiceRecognition(peer) }
    }

    @Test fun selectedAudioLossAfterReadinessIsTerminal() {
        ready()
        event(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED, BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.poll().phase)
    }

    @Test fun liveStatePreventsStaleConnectedEventKeepingReady() {
        ready()
        every { proxy.isAudioConnected(peer) } returns false
        event(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED, BluetoothHeadset.STATE_AUDIO_CONNECTED)
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.poll().phase)
    }

    @Test fun permissionRevocationAfterReadinessIsNonfatalToParent() {
        ready()
        context.permission = false
        if (Build.VERSION.SDK_INT < 31) {
            every { proxy.isAudioConnected(peer) } throws SecurityException("legacy permission revoked")
            event(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED, BluetoothHeadset.STATE_AUDIO_CONNECTED)
        }
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.poll().phase)
        assertTrue(route.status.permissionRequired)
        verify(exactly = 1) { proxy.stopVoiceRecognition(peer) }
        assertEquals(1, context.unregisters)
    }

    @Test fun concurrentStopWhileVoiceRequestIsInFlightReleasesAfterItReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        every { proxy.startVoiceRecognition(peer) } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }
        route.start()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        val worker = thread { try { route.poll() } catch (t: Throwable) { error.set(t) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        running = false
        val closer = thread { try { route.close() } catch (t: Throwable) { error.set(t) } }
        release.countDown()
        worker.join(5_000); closer.join(5_000)
        assertFalse(worker.isAlive)
        assertFalse(closer.isAlive)
        assertNull(error.get())
        assertEquals(HfpMicrophoneRoute.Phase.CLOSED, route.status.phase)
        verify(exactly = 1) { proxy.startVoiceRecognition(peer) }
        verify(exactly = 1) { proxy.stopVoiceRecognition(peer) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun matchingUsesOnlyScoAndExactPeerAddress() {
        ready()
        val other = input(2, OTHER_ADDRESS)
        val ble = input(3, ADDRESS, AudioDeviceInfo.TYPE_BLE_HEADSET)
        assertEquals(HfpMicrophoneRoute.InputMatch.MATCH, route.matchInput(target, listOf(target, other)))
        assertEquals(HfpMicrophoneRoute.InputMatch.DIFFERENT, route.matchInput(other, listOf(target, other)))
        assertEquals(HfpMicrophoneRoute.InputMatch.DIFFERENT, route.matchInput(ble, listOf(ble)))
    }

    @Test fun addresslessMatchingNeedsSoleProfileAndSoleCurrentScoInput() {
        ready()
        val blank = input(5)
        val other = input(6)
        assertEquals(HfpMicrophoneRoute.InputMatch.MATCH, route.matchInput(blank, listOf(blank)))
        assertEquals(HfpMicrophoneRoute.InputMatch.UNKNOWN, route.matchInput(blank, listOf(blank, other)))
        assertEquals(HfpMicrophoneRoute.InputMatch.UNKNOWN, route.matchInput(blank, emptyList()))
        // A newly connected peer invalidates the one-profile proof, even before the worker polls.
        event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED, headset(OTHER_ADDRESS))
        assertEquals(HfpMicrophoneRoute.InputMatch.UNKNOWN, route.matchInput(blank, listOf(blank)))
    }

    @Test fun sameProductNameIsNeverEvidenceWithMultipleProfiles() {
        every { proxy.connectedDevices } returns listOf(peer, headset(OTHER_ADDRESS))
        ready()
        val blank = input(5)
        assertEquals(HfpMicrophoneRoute.InputMatch.UNKNOWN, route.matchInput(blank, listOf(blank)))
    }

    @Test
    @Config(sdk = [31, 36])
    fun stopDuringLastPermissionCheckWinsBeforeVoiceRequest() {
        route.start()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        var checks = 0
        context.onPermissionCheck = { if (++checks == 2) running = false }
        route.poll()
        assertEquals(HfpMicrophoneRoute.Phase.CLOSED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun proxyDisconnectDuringDeviceLookupCannotOpenAudio() {
        every { proxy.connectedDevices } answers {
            listener.captured.onServiceDisconnected(BluetoothProfile.HEADSET)
            listOf(peer)
        }
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
    }

    @Test fun proxyDisconnectDuringAudioRequestCannotPublishReady() {
        every { proxy.startVoiceRecognition(peer) } answers {
            listener.captured.onServiceDisconnected(BluetoothProfile.HEADSET)
            true
        }
        every { proxy.isAudioConnected(peer) } returns true
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 1) { proxy.stopVoiceRecognition(peer) }
    }

    @Test fun duplicateAndAdditionalProxiesCannotReplaceOwnership() {
        route.start()
        val extra = mockk<BluetoothHeadset>(relaxed = true)
        repeat(3) {
            listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
            listener.captured.onServiceConnected(BluetoothProfile.HEADSET, extra)
        }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, extra) }
        route.poll()
        verify(exactly = 1) { proxy.startVoiceRecognition(peer) }
        verify(exactly = 0) { extra.startVoiceRecognition(any()) }
        route.close()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun closeConsumesQueuedProxyEvenIfWorkerNeverPolls() {
        route.start()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        route.close()
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        assertEquals(1, context.unregisters)
    }

    @Test fun permissionFailureDuringPeerLookupStillClosesAcquiredProxy() {
        every { proxy.connectedDevices } throws SecurityException("private $ADDRESS")
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        assertTrue(route.status.permissionRequired)
        assertFalse(route.status.toString().contains(ADDRESS))
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
    }

    @Test fun profileCallPartiallyDeliversThenThrowsAndStillClosesProxy() {
        every { adapter.getProfileProxy(any(), capture(listener), BluetoothProfile.HEADSET) } answers {
            listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
            throw IllegalStateException("partial delivery")
        }
        route.start()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
        verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        assertEquals(1, context.unregisters)
    }

    @Test fun setupQueriesAreThrottledAndReadinessDoesNotContinuouslyPoll() {
        acquire()
        repeat(100) { now++; route.poll() }
        verify(exactly = 1) { proxy.isAudioConnected(peer) }
        now = 200
        every { proxy.isAudioConnected(peer) } returns true
        assertEquals(HfpMicrophoneRoute.Phase.READY, route.poll().phase)
        repeat(100) { now += 1_000; route.poll() }
        assertEquals(HfpMicrophoneRoute.Phase.READY, route.status.phase)
        verify(exactly = 2) { proxy.isAudioConnected(peer) }
        verify(exactly = 1) { proxy.connectedDevices }
    }

    @Test fun nullTargetUsesOnlyAnUnambiguousClassicProfile() {
        route = newRoute(null)
        ready()
        assertEquals(HfpMicrophoneRoute.InputMatch.MATCH, route.matchInput(target, listOf(target)))
    }

    @Test fun blePeerWithExactAddressCanUseItsOwnClassicTransport() {
        route = newRoute(input(2, ADDRESS, AudioDeviceInfo.TYPE_BLE_HEADSET))
        ready()
        assertEquals(HfpMicrophoneRoute.InputMatch.MATCH, route.matchInput(target, listOf(target)))
    }

    @Test fun addresslessCandidateCannotHideAnotherPeersAddressInInventory() {
        ready()
        val blank = input(8)
        val other = input(8, OTHER_ADDRESS)
        assertEquals(HfpMicrophoneRoute.InputMatch.DIFFERENT, route.matchInput(blank, listOf(other)))
        route.close()
        assertEquals(HfpMicrophoneRoute.InputMatch.UNKNOWN, route.matchInput(target, listOf(target)))
    }

    @Test fun deviceNameCannotExposeRawBluetoothAddress() {
        every { peer.name } returns "Headset $ADDRESS"
        ready()
        assertEquals("Headset [redacted]", route.status.deviceName)
        assertFalse(route.status.toString().contains(ADDRESS))
    }

    @Test
    @Config(sdk = [31, 36])
    fun deadlineReachedDuringFinalPermissionCheckCannotOpenAudio() {
        route.start()
        listener.captured.onServiceConnected(BluetoothProfile.HEADSET, proxy)
        var checks = 0
        context.onPermissionCheck = { if (++checks == 2) now = 8_000 }
        route.poll()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
    }

    @Test fun cancellationDuringSlowLookupWinsOverSimultaneousTimeout() {
        every { proxy.connectedDevices } answers {
            now = 8_000
            running = false
            listOf(peer)
        }
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.CLOSED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
    }

    @Test fun changedPeerInventoryDuringAddresslessLookupCannotOpenArbitraryHeadset() {
        route = newRoute(input(2))
        every { proxy.connectedDevices } answers {
            event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED,
                headset(OTHER_ADDRESS))
            listOf(peer) // Snapshot raced the new peer's connection.
        }
        acquire()
        assertEquals(HfpMicrophoneRoute.Phase.FAILED, route.status.phase)
        verify(exactly = 0) { proxy.startVoiceRecognition(any()) }
    }

    companion object {
        private const val ADDRESS = "00:11:22:33:44:55"
        private const val OTHER_ADDRESS = "00:99:88:77:66:55"
    }
}
