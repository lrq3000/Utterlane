package io.github.lrq3000.utterlane.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Borrowed AudioRecord and communication-route resources belong exclusively to
 * the capture worker. Platform callbacks only invalidate cached routing; they
 * cannot close/reopen a recorder or resurrect a stopped session.
 */
@Suppress("DEPRECATION")
class AndroidCaptureRoute(
    private val context: Context,
    private val controller: AudioInputController,
    initial: AudioInputState,
    private val shouldContinue: () -> Boolean,
    private val onState: (CaptureInputState) -> Unit,
    private val options: MicrophoneOptions = MicrophoneOptions.STANDARD,
    private val onDiagnostic: (String) -> Unit = {}
) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val target = initial.selected
    private val useHfp = target.bluetooth && options.route == BluetoothCaptureRoute.HFP_VOICE_RECOGNITION
    private class SourceBinding(val input: AudioInput) { var removed = false }
    private val bindingLock = Any()
    private var sourceBinding = target.takeIf { it.inputId != null }?.let(::SourceBinding)
    private val deviceVersion = AtomicLong()
    private var inventoryVersion = -1L
    private val policy = CaptureRoutePolicy(target, SystemClock::uptimeMillis, if (useHfp) 8000 else 5000)
    private var hfp: HfpMicrophoneRoute? = null
    private var inventory = initial
    private var inputs = emptyMap<Int, AudioDeviceInfo>()
    private var communications = emptyMap<Int, AudioDeviceInfo>()
    private var phoneId: Int? = null
    private var currentCommunicationId: Int? = null
    private var actual: AudioInput? = null
    private var targetAvailable = true
    private val dirty = AtomicBoolean(true)
    private val targetRemoved = AtomicBoolean(false)
    private val generation = AtomicLong()
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var deviceListener: AudioDeviceCallback? = null
    private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var scoReceiver: BroadcastReceiver? = null
    @Volatile private var scoConnected = false
    private var communicationRequested = false
    private var modeRequested = false
    private var scoRoutingSet = false
    private var releaseAttempted = false
    private var started = false
    private var closed = false
    private var lastPreference: Pair<String, Int?>? = null
    private var published: CaptureInputState? = null
    private var publishedHfp: HfpMicrophoneRoute.Status? = null

    val isFallback: Boolean get() = policy.isFallback

    fun attach(record: AudioRecord) {
        if (deviceListener == null) {
            // The active recording has its own connection watch. Next-session
            // selection may already refer to another device, and reconnection can
            // erase the missing snapshot before the capture worker sees it.
            val listener = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = devicePortsChanged(emptyArray())
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = devicePortsChanged(removedDevices)
            }
            deviceListener = listener
            try { manager.registerAudioDeviceCallback(listener, Handler(Looper.getMainLooper())) }
            catch (e: RuntimeException) {
                Log.w(TAG, "Could not watch active input; using phone microphone", e)
                policy.fallback(InputFallbackReason.UNAVAILABLE)
            }
        }
        val owner = generation.incrementAndGet()
        val listener = AudioRouting.OnRoutingChangedListener {
            if (generation.get() == owner) dirty.set(true)
        }
        routingListener = listener
        record.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
        lastPreference = null
        actual = null
        policy.recorderReopened()
        refreshInventory()
        if (targetRemoved.get()) policy.fallback(InputFallbackReason.DISCONNECTED)
        policy.observe(null, targetAvailable, frames = false)
        // Start capturing now, even if Bluetooth activation needs several seconds.
        prefer(record, if (target.bluetooth || policy.isFallback) AudioInput.PHONE_KEY else target.key)
        dirty.set(true)
    }

    fun started() {
        if (started || !shouldContinue()) return
        started = true
        if (!target.bluetooth || policy.isFallback) return
        try {
            if (manager.mode == AudioManager.MODE_IN_CALL) {
                policy.fallback(InputFallbackReason.UNAVAILABLE)
                return
            }
            if (useHfp) {
                val device = target.communicationId?.let(communications::get) ?: target.inputId?.let(inputs::get)
                hfp = HfpMicrophoneRoute(context, device, shouldContinue, { dirty.set(true) }).also { it.start() }
                if (hfp?.status?.phase == HfpMicrophoneRoute.Phase.FAILED) {
                    policy.fallback(InputFallbackReason.UNAVAILABLE)
                    return
                }
            }
            // NORMAL does not manufacture a claim for a mode owned by another
            // app. Modern standard routing always needs communication mode;
            // diagnostics expose any requested/observed difference.
            val needsCommunication = (!useHfp && Build.VERSION.SDK_INT >= 31) || options.mode == BluetoothAudioMode.IN_COMMUNICATION
            if (needsCommunication && manager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                modeRequested = true
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (!shouldContinue()) return
            if (useHfp) { dirty.set(true); return }
            if (Build.VERSION.SDK_INT >= 31) {
                val listener = AudioManager.OnCommunicationDeviceChangedListener { dirty.set(true) }
                communicationListener = listener
                manager.addOnCommunicationDeviceChangedListener(Executor { it.run() }, listener)
                val device = target.communicationId?.let(communications::get)
                // A binder call can fail after the service accepts a request.
                // Retain ownership on exceptions so fallback/close still clears it;
                // an explicit false result, in contrast, rejects the request.
                if (device != null) {
                    communicationRequested = true
                    communicationRequested = manager.setCommunicationDevice(device)
                }
                if (!communicationRequested) policy.fallback(InputFallbackReason.UNAVAILABLE)
            } else {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        scoConnected = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
                        dirty.set(true)
                    }
                }
                scoReceiver = receiver
                val sticky = ContextCompat.registerReceiver(context, receiver,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
                scoConnected = sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
                // Mark ownership before requesting: a partial platform failure
                // still requires stopBluetoothSco during cleanup.
                communicationRequested = true
                manager.startBluetoothSco()
            }
            dirty.set(true)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Bluetooth route request failed; using phone microphone", e)
            policy.fallback(InputFallbackReason.UNAVAILABLE)
        }
    }

    fun beforeRead(record: AudioRecord, silenced: Boolean) {
        if (!shouldContinue()) return
        hfp?.poll()?.let { if (it.phase == HfpMicrophoneRoute.Phase.FAILED) policy.fallback(InputFallbackReason.UNAVAILABLE) }
        if (targetRemoved.get()) policy.fallback(InputFallbackReason.DISCONNECTED)
        if (dirty.getAndSet(false)) {
            refreshInventory()
            refreshActual(record)
        }
        policy.observe(actual, targetAvailable, frames = false, silenced = silenced)
        if (policy.isFallback) releaseCommunication()
        val key = when {
            policy.isFallback -> AudioInput.PHONE_KEY
            !target.bluetooth -> target.key
            communicationReady() -> target.key
            else -> AudioInput.PHONE_KEY
        }
        if (shouldContinue()) prefer(record, key)
        publish()
        check(!policy.failed) { context.getString(io.github.lrq3000.utterlane.R.string.audio_input_fallback_failed) }
    }

    fun afterRead(record: AudioRecord, count: Int, silenced: Boolean) {
        // Query after the first read as well: routedDevice is not valid before
        // recording starts, and a routing callback can arrive slightly later.
        try {
            if (dirty.getAndSet(false)) { refreshInventory(); refreshActual(record) }
            else if (actual == null) refreshActual(record)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not confirm input route; requesting phone fallback", e)
            actual = null
            policy.fallback(InputFallbackReason.UNAVAILABLE)
        }
        if (targetRemoved.get()) policy.fallback(InputFallbackReason.DISCONNECTED)
        if (count == AudioRecord.ERROR_DEAD_OBJECT) policy.fallback(InputFallbackReason.UNAVAILABLE)
        val transportReady = !useHfp || policy.isFallback || hfp?.status?.phase == HfpMicrophoneRoute.Phase.READY
        policy.observe(actual, targetAvailable, frames = count > 0 && transportReady, silenced = silenced)
        publish()
        // Never throw after a successful read: that PCM still needs to reach the
        // writer. A failed recovery is reported before the next native read.
    }

    fun takeReopenRequest(): Boolean = policy.takeReopenRequest()

    private fun refreshInventory() {
        val version = deviceVersion.get()
        // Resolve native ports independently of the next-session settings Flow.
        // A routing callback may precede its inventory refresh/persistence job.
        inventory = AudioInputState(controller.currentInputs(), inventory.preferences)
        inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).associateBy { it.id }
        phoneId = inputs.values.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.id
        communications = if (Build.VERSION.SDK_INT >= 31) {
            try { manager.availableCommunicationDevices.associateBy { it.id } }
            catch (e: SecurityException) { Log.w(TAG, "Communication devices unavailable", e); emptyMap() }
        } else emptyMap()
        currentCommunicationId = if (Build.VERSION.SDK_INT >= 31) manager.communicationDevice?.id else null
        targetAvailable = when {
            target.isPhone -> true
            target.communicationId != null -> communications.containsKey(target.communicationId)
            else -> target.inputId != null && inputs.containsKey(target.inputId)
        }
        inventoryVersion = version
    }

    private fun refreshActual(record: AudioRecord) {
        // Native queries stay outside the binding lock. A removal can arrive
        // during this query, so recheck the inventory generation while publishing
        // the binding under the same lock used by device callbacks.
        val routed = record.routedDevice
        synchronized(bindingLock) {
            if (inventoryVersion != deviceVersion.get()) {
                actual = null
                dirty.set(true)
                return
            }
            val bound = validSourceBindingLocked()
            val hfpMatch = if (useHfp && routed != null && !policy.isFallback)
                hfp?.matchInput(routed, inputs.values) else null
            if (hfpMatch == HfpMicrophoneRoute.InputMatch.DIFFERENT && routed != null &&
                AndroidAudioInputDevices.isBluetooth(routed.type)) {
                sourceBinding?.takeIf { it.input.inputId == routed.id }?.removed = true
                policy.fallback(InputFallbackReason.ROUTE_CHANGED)
            }
            actual = when {
                routed == null -> null
                routed.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInput(AudioInput.PHONE_KEY, "", false, routed.id)
                hfpMatch == HfpMicrophoneRoute.InputMatch.MATCH -> AudioInput(target.key,
                    hfp?.status?.deviceName ?: target.name, true, routed.id, inventory.byInputId[routed.id]?.communicationId)
                hfpMatch == HfpMicrophoneRoute.InputMatch.DIFFERENT -> AudioInput("actual:${routed.id}",
                    routed.productName.toString(), AndroidAudioInputDevices.isBluetooth(routed.type), routed.id)
                !policy.isFallback && bound?.inputId == routed.id && hfpMatch != HfpMicrophoneRoute.InputMatch.DIFFERENT -> bound
                else -> inventory.byInputId[routed.id] ?: AudioInput("actual:${routed.id}", routed.productName.toString(),
                    AndroidAudioInputDevices.isBluetooth(routed.type), routed.id)
            }
            actual?.takeIf { !policy.isFallback && it.key == target.key && it.inputId != null }?.let {
                if (bound?.inputId != it.inputId) sourceBinding = SourceBinding(it)
            }
        }
    }

    private fun devicePortsChanged(removed: Array<out AudioDeviceInfo>) {
        synchronized(bindingLock) {
            val bound = sourceBinding
            if (bound != null && removed.any { it.id == bound.input.inputId }) bound.removed = true
            if (removed.any { it.id == target.connectionId }) targetRemoved.set(true)
            deviceVersion.incrementAndGet()
        }
        dirty.set(true)
    }

    /** Caller holds bindingLock and has checked the inventory's generation. */
    private fun validSourceBindingLocked(): AudioInput? {
        val bound = sourceBinding?.takeUnless { it.removed } ?: return null
        val mapped = inventory.byInputId[bound.input.inputId]
        // Keep a proven source when a new duplicate makes catalogue matching
        // ambiguous. Positive contrary evidence invalidates it permanently: later
        // ambiguity must not resurrect a disproved association.
        if (bound.input.inputId !in inputs || (mapped != null && mapped.connectionId != bound.input.connectionId)) {
            bound.removed = true
            return null
        }
        return bound.input
    }

    private fun communicationReady(): Boolean {
        if (useHfp) return hfp?.status?.phase == HfpMicrophoneRoute.Phase.READY
        if (!communicationRequested) return false
        return if (Build.VERSION.SDK_INT >= 31) currentCommunicationId == target.communicationId
        else scoConnected
    }

    private fun prefer(record: AudioRecord, key: String) {
        val inputId = synchronized(bindingLock) {
            if (inventoryVersion != deviceVersion.get()) { dirty.set(true); return }
            if (key == AudioInput.PHONE_KEY) phoneId
            else validSourceBindingLocked()?.inputId ?: inventory.byKey[key]?.inputId
        }
        val preference = key to inputId
        if (lastPreference == preference) return
        val device = inputId?.let(inputs::get)
        // A null Bluetooth source allows Android's established communication
        // route to pick its source; null must never mean "Phone microphone".
        if (key == AudioInput.PHONE_KEY && device == null) error(context.getString(io.github.lrq3000.utterlane.R.string.audio_input_phone_unavailable))
        val accepted = try {
            if (key == target.key && target.bluetooth && (useHfp || Build.VERSION.SDK_INT < 31) && communicationReady() && !scoRoutingSet) {
                scoRoutingSet = true
                manager.isBluetoothScoOn = true
            }
            record.setPreferredDevice(device)
        }
        catch (e: RuntimeException) {
            if (key == AudioInput.PHONE_KEY) throw e
            Log.w(TAG, "Selected input disappeared during routing; using phone", e)
            false
        }
        if (!accepted) {
            if (key == AudioInput.PHONE_KEY) error(context.getString(io.github.lrq3000.utterlane.R.string.audio_input_phone_unavailable))
            policy.fallback(InputFallbackReason.UNAVAILABLE)
            dirty.set(true)
            return
        }
        lastPreference = preference
        actual = null
        dirty.set(true)
    }

    private fun publish(forceDiagnostic: Boolean = false) {
        val changed = published != policy.state
        if (changed) { published = policy.state; onState(policy.state) }
        val hfpState = hfp?.status
        if (changed || publishedHfp != hfpState || forceDiagnostic) {
            publishedHfp = hfpState
            val mode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
            onDiagnostic((if (closed) "Capture route teardown attempted.\n" else "") + if (!target.bluetooth) "Bluetooth route not requested for this input" else
                "Requested Bluetooth: ${options.route}; requested mode=${options.mode}; observed mode=$mode\n" +
                    (hfpState?.let { "HFP: ${it.phase}; request=${it.requestAccepted}; ${it.detail}" }
                        ?: "Standard Bluetooth request; actual microphone confirmation is independent"))
        }
    }

    fun detach(record: AudioRecord) {
        generation.incrementAndGet()
        routingListener?.let { record.removeOnRoutingChangedListener(it) }
        routingListener = null
    }

    private fun releaseCommunication(finalAttempt: Boolean = false) {
        // Fallback can call this on every PCM block. Try cleanup once there and
        // retain failed ownership flags for one final attempt at close, rather
        // than either losing cleanup permanently or hammering the audio service.
        if (releaseAttempted && !finalAttempt) return
        releaseAttempted = true
        hfp?.close()
        if (scoRoutingSet) {
            cleanup("Could not clear SCO routing") { manager.isBluetoothScoOn = false; scoRoutingSet = false }
        }
        if (communicationRequested) {
            cleanup("Could not release communication route") {
                if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice() else manager.stopBluetoothSco()
                communicationRequested = false
            }
        }
        if (modeRequested) {
            // MODE_NORMAL removes this app's mode claim; telephony/other clients
            // retain their own higher-priority claims in Android's audio service.
            cleanup("Could not release communication mode") { manager.mode = AudioManager.MODE_NORMAL; modeRequested = false }
        }
    }

    /** One failed platform cleanup must not prevent the other owned resources being released. */
    private inline fun cleanup(message: String, action: () -> Unit) {
        try { action() } catch (e: RuntimeException) { Log.w(TAG, message, e) }
    }

    override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        releaseCommunication(finalAttempt = true)
        deviceListener?.let { listener ->
            cleanup("Could not unregister input listener") { manager.unregisterAudioDeviceCallback(listener) }
        }
        deviceListener = null
        if (Build.VERSION.SDK_INT >= 31) communicationListener?.let { listener ->
            cleanup("Could not unregister communication listener") { manager.removeOnCommunicationDeviceChangedListener(listener) }
        }
        communicationListener = null
        scoReceiver?.let { receiver ->
            cleanup("Could not unregister SCO receiver") { context.unregisterReceiver(receiver) }
        }
        scoReceiver = null
        publish(forceDiagnostic = true)
    }

    companion object { private const val TAG = "CaptureRoute" }
}
