package io.github.lrq3000.utterlane.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One recording's classic HFP transport. Call [start] after Phone capture starts,
 * then [poll] on the capture worker, including while CONNECTING (the deadline is
 * worker-driven). READY confirms the HFP link only; the parent must also validate
 * AudioRecord's actual source and frames before claiming headset capture.
 *
 * This owner never changes AudioManager mode or SCO routing. Callbacks only retain
 * a proxy / coalesce events and invoke [onChanged], which must be a lightweight
 * worker signal, not inline routing work. [close] is thread-safe; normally the
 * worker calls it after the parent's Stop flag makes [shouldContinue] false.
 * Instances are single-use. Failure details survive close for Phone-fallback UI.
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission") // Gate CONNECT and also handle revocation at each Binder boundary.
class HfpMicrophoneRoute(
    private val context: Context,
    private val target: AudioDeviceInfo?,
    private val shouldContinue: () -> Boolean,
    private val onChanged: () -> Unit = {},
    private val clock: () -> Long = SystemClock::uptimeMillis
) : Closeable {
    enum class Phase { IDLE, CONNECTING, READY, FAILED, CLOSED }
    data class Status(
        val phase: Phase,
        val detail: String,
        val deviceName: String? = null,
        val requestAccepted: Boolean? = null,
        val permissionRequired: Boolean = false
    )
    enum class InputMatch { MATCH, DIFFERENT, UNKNOWN }

    @Volatile private var current = Status(Phase.IDLE, "HFP idle")
    val status: Status get() = current

    // Never hold eventLock across a platform call: a Binder callback can arrive
    // while the worker is in that call. The worker lock serializes audio ownership
    // and cleanup; closeRequested fences new work before close waits for that lock.
    private val workerLock = Any()
    private val eventLock = Any()
    private val closeRequested = AtomicBoolean()
    private val terminal = AtomicBoolean()
    private val audioDirty = AtomicBoolean()
    private val profileLost = AtomicBoolean()
    private val connectionLost = AtomicBoolean()
    private val audioLost = AtomicBoolean()
    private val solePeerInvalidated = AtomicBoolean()
    private class ProxyLease(val adapter: BluetoothAdapter, val id: Int, val proxy: BluetoothProfile) {
        var released = false // eventLock; retain one tombstone to ignore duplicate callbacks.
    }
    private var lease: ProxyLease? = null // eventLock; at most one retained proxy.
    private var lastOrphan: BluetoothProfile? = null // bounded duplicate suppression.
    private var profile: BluetoothHeadset? = null
    @Volatile private var selected: BluetoothDevice? = null
    private var selectedAddress: String? = null
    private var solePeer = false
    private var ownsVoice = false
    private var receiverOwned = false
    private var startedAt = 0L
    private var nextAudioCheck = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (terminal.get() || closeRequested.get()) return
            val action = intent?.action ?: return
            if (action != BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED &&
                action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device != selected) {
                // Another peer cannot make this route ready or failed. It can,
                // however, invalidate an addressless one-peer association. Keep
                // that proof invalid for this session rather than re-enumerating.
                if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                    solePeerInvalidated.set(true)
                    signal()
                }
                return
            }
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED &&
                state == BluetoothProfile.STATE_DISCONNECTED) connectionLost.set(true)
            if (action == BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED &&
                state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED && current.phase == Phase.READY) {
                audioLost.set(true)
            }
            // Loss is latched separately: a rapid reconnect cannot erase it before
            // the worker consumes the event. CONNECTED itself is never readiness.
            audioDirty.set(true)
            signal()
        }
    }

    /** Request the profile asynchronously; never wait for a headset or audio. */
    fun start() = synchronized(workerLock) {
        if (current.phase != Phase.IDLE || !continueSession()) return@synchronized
        startedAt = clock()
        publish(current.copy(phase = Phase.CONNECTING, detail = "Requesting HFP profile"))
        try {
            if (!permissionGranted()) return@synchronized
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                fail("Bluetooth adapter unavailable or disabled")
                return@synchronized
            }
            if (!continueSession()) return@synchronized
            val filter = IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED).apply {
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            // Retain before calls which can partially succeed and then throw.
            receiverOwned = true
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            if (!continueSession() || !withinDeadline()) return@synchronized
            val accepted = adapter.getProfileProxy(context, profileListener(adapter), BluetoothProfile.HEADSET)
            if (!continueSession()) return@synchronized
            if (!accepted) fail("HFP profile proxy request rejected")
        } catch (e: RuntimeException) {
            platformFailure("HFP profile setup", e)
        }
    }

    private fun profileListener(adapter: BluetoothAdapter) = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
            var orphan = false
            var queued = false
            synchronized(eventLock) {
                if (lease?.proxy === proxy || lastOrphan === proxy) return
                if (terminal.get() || closeRequested.get() || !shouldContinue() || lease != null) {
                    lastOrphan = proxy
                    orphan = true
                } else {
                    lease = ProxyLease(adapter, id, proxy)
                    queued = true
                }
            }
            // Orphans cannot wait for a worker that may already have exited. This
            // releases only the delivered proxy, never audio or another session.
            if (orphan) releaseProxy(adapter, id, proxy)
            if (queued) signal()
        }

        override fun onServiceDisconnected(id: Int) {
            if (id != BluetoothProfile.HEADSET || terminal.get() || closeRequested.get()) return
            profileLost.set(true)
            signal()
        }
    }

    /** Consume callback state and confirm live audio on the worker, never the main thread. */
    fun poll(): Status = synchronized(workerLock) {
        if (!continueSession() || current.phase == Phase.IDLE) return@synchronized current
        try {
            if (!permissionGranted() || !withinDeadline()) return@synchronized current
            if (profile == null) {
                val acquired = synchronized(eventLock) { lease } ?: return@synchronized current
                val hfp = acquired.proxy as? BluetoothHeadset
                if (acquired.id != BluetoothProfile.HEADSET || hfp == null) {
                    fail("HFP profile unavailable")
                    return@synchronized current
                }
                profile = hfp
                requestAudio(hfp)
            }
            if (!continueSession()) return@synchronized current
            val hfp = profile ?: return@synchronized current
            val peer = selected ?: return@synchronized current
            val dirty = audioDirty.getAndSet(false)
            // Bounded setup polling covers omitted broadcasts. Once READY, only
            // selected-peer events query audio; idle frame polls do no Binder work
            // beyond the permission gate and never enumerate connected devices.
            if (dirty || (current.phase == Phase.CONNECTING && clock() >= nextAudioCheck)) {
                nextAudioCheck = clock() + AUDIO_CHECK_INTERVAL_MS
                val connected = hfp.getConnectionState(peer) == BluetoothProfile.STATE_CONNECTED &&
                    hfp.isAudioConnected(peer)
                if (!continueSession() || !withinDeadline()) return@synchronized current
                if (connected) publish(current.copy(phase = Phase.READY, detail = "HFP audio connected"))
                else if (current.phase == Phase.READY) fail("HFP audio disconnected")
            }
        } catch (e: RuntimeException) {
            platformFailure("HFP audio setup or state query", e)
        }
        current
    }

    private fun requestAudio(hfp: BluetoothHeadset) {
        val devices = hfp.connectedDevices
        val address = addressOf(target)
        val classic = target == null || target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val peer = if (address != null) devices.firstOrNull { sameAddress(it.address, address) }
            else if (classic) devices.singleOrNull() else null
        if (peer == null) {
            fail(when {
                devices.isEmpty() -> "No connected HFP headset"
                address != null -> "Selected device has no matching connected HFP profile"
                !classic -> "Selected non-classic device cannot be associated with HFP without an address"
                else -> "Multiple HFP headsets; selected device is ambiguous"
            })
            return
        }
        selected = peer
        selectedAddress = peer.address?.takeIf { it.isNotBlank() }
        solePeer = devices.size == 1
        val name = peer.name ?: target?.productName?.toString()
        publish(current.copy(deviceName = name?.let { MAC_ADDRESS.replace(it, "[redacted]") }))
        // Stop or timeout during device lookup / publication still wins before
        // opening audio. Permission can also change since the profile request.
        if (!permissionGranted()) return
        if (address == null && solePeerInvalidated.get()) {
            fail("HFP peers changed during addressless selection; headset is ambiguous")
            return
        }
        if (!continueSession() || !withinDeadline()) return
        ownsVoice = true
        val accepted = hfp.startVoiceRecognition(peer)
        ownsVoice = accepted // A false rejection, unlike an exception, owns no audio.
        current = current.copy(requestAccepted = accepted)
        if (!continueSession()) return
        if (!accepted) {
            fail("HFP voice recognition request rejected")
            return
        }
        audioDirty.set(true)
        publish(current.copy(detail = "HFP request accepted; waiting for audio"))
    }

    /**
     * Match only classic SCO sources. UNKNOWN is not proof: the parent may use its
     * generation-fenced source binding for it, but must never override DIFFERENT.
     * Exact addresses are O(1); an addressless proof scans only the supplied input
     * snapshot (O(n)), not Bluetooth's connected-device list or product names.
     */
    fun matchInput(device: AudioDeviceInfo, inputs: Collection<AudioDeviceInfo>): InputMatch = synchronized(workerLock) {
        if (device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO || !device.isSource) return@synchronized InputMatch.DIFFERENT
        if (terminal.get() || closeRequested.get() || selected == null) return@synchronized InputMatch.UNKNOWN
        val address = addressOf(device)
        val expected = selectedAddress
        if (address != null && expected != null) {
            return@synchronized if (sameAddress(address, expected)) InputMatch.MATCH else InputMatch.DIFFERENT
        }
        if (address != null || !solePeer || solePeerInvalidated.get()) return@synchronized InputMatch.UNKNOWN
        var soleInput: AudioDeviceInfo? = null
        for (input in inputs) {
            if (!input.isSource || input.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue
            if (soleInput != null) return@synchronized InputMatch.UNKNOWN
            soleInput = input
        }
        // Require the candidate to be present now, and do not let its missing
        // address hide a contradictory address in the inventory for that port.
        if (soleInput?.id != device.id) return@synchronized InputMatch.UNKNOWN
        val inventoryAddress = addressOf(soleInput)
        if (inventoryAddress != null && !sameAddress(inventoryAddress, expected)) return@synchronized InputMatch.DIFFERENT
        InputMatch.MATCH
    }

    override fun close() {
        closeRequested.set(true)
        synchronized(workerLock) { finishClose() }
    }

    private fun continueSession(): Boolean {
        if (terminal.get()) return false
        if (closeRequested.get() || !shouldContinue()) {
            finishClose()
            return false
        }
        // Recheck callback latches at each request/readiness boundary, not only
        // on entry to poll: a profile can disappear during a preceding Binder call.
        if (profileLost.get() || connectionLost.get() || audioLost.get()) {
            fail(when {
                profileLost.get() -> "HFP profile disconnected"
                connectionLost.get() -> "Selected HFP headset disconnected"
                else -> "HFP audio disconnected"
            })
            return false
        }
        return true
    }

    private fun withinDeadline(): Boolean {
        if (current.phase == Phase.CONNECTING && clock() - startedAt >= SETUP_TIMEOUT_MS) {
            fail("Timed out waiting for HFP audio (8 seconds)")
            return false
        }
        return true
    }

    private fun permissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            fail("BLUETOOTH_CONNECT permission required", permissionRequired = true)
            return false
        }
        return true
    }

    private fun platformFailure(operation: String, error: RuntimeException) {
        if (!continueSession()) return
        // OEM exception messages can include raw addresses. Preserve the stage and
        // error kind without publishing the message, stack, or device.toString().
        fail("$operation failed (${error.javaClass.simpleName})", error is SecurityException)
    }

    private fun fail(detail: String, permissionRequired: Boolean = false) {
        // An explicit Stop takes precedence over a simultaneous setup failure;
        // cleanup must not turn a cancelled session into a new fallback warning.
        if (closeRequested.get() || !shouldContinue()) {
            finishClose()
            return
        }
        if (terminal.getAndSet(true)) return
        current = current.copy(phase = Phase.FAILED, detail = detail, permissionRequired = permissionRequired)
        cleanup()
        signal()
    }

    private fun finishClose() {
        if (terminal.getAndSet(true)) return
        current = current.copy(phase = Phase.CLOSED, detail = "HFP route closed")
        cleanup()
        signal()
    }

    private fun cleanup() {
        val hfp = profile
        val peer = selected
        if (ownsVoice) {
            ownsVoice = false
            if (hfp != null && peer != null) runCatching { hfp.stopVoiceRecognition(peer) }
        }
        val acquired = synchronized(eventLock) {
            lease?.takeUnless { it.released }?.also { it.released = true }
        }
        if (acquired != null) releaseProxy(acquired.adapter, acquired.id, acquired.proxy)
        if (receiverOwned) {
            receiverOwned = false
            runCatching { context.unregisterReceiver(receiver) }
        }
        profile = null
        selected = null
    }

    private fun releaseProxy(adapter: BluetoothAdapter, id: Int, proxy: BluetoothProfile) {
        runCatching { adapter.closeProfileProxy(id, proxy) }
    }

    private fun publish(next: Status) {
        if (current == next) return
        current = next
        signal()
    }

    private fun signal() { runCatching { onChanged() } }

    private fun addressOf(device: AudioDeviceInfo?): String? =
        if (Build.VERSION.SDK_INT >= 28) device?.address?.takeIf { it.isNotBlank() } else null

    private fun sameAddress(first: String?, second: String?): Boolean =
        !first.isNullOrBlank() && !second.isNullOrBlank() && first.equals(second, ignoreCase = true)

    companion object {
        private const val SETUP_TIMEOUT_MS = 8_000L
        private const val AUDIO_CHECK_INTERVAL_MS = 200L
        private val MAC_ADDRESS = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
    }
}
