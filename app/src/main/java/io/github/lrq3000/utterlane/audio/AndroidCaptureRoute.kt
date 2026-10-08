package io.github.lrq3000.utterlane.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
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
    private val onState: (CaptureInputState) -> Unit
) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val target = initial.selected
    private val policy = CaptureRoutePolicy(target, SystemClock::uptimeMillis)
    private var inventory = initial
    private var inputs = emptyMap<Int, AudioDeviceInfo>()
    private var communications = emptyMap<Int, AudioDeviceInfo>()
    private var phoneId: Int? = null
    private var currentCommunicationId: Int? = null
    private var actual: AudioInput? = null
    private var targetAvailable = true
    private val dirty = AtomicBoolean(true)
    private val generation = AtomicLong()
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var scoReceiver: BroadcastReceiver? = null
    @Volatile private var scoConnected = false
    private var communicationRequested = false
    private var modeRequested = false
    private var scoRoutingSet = false
    private var started = false
    private var closed = false
    private var lastPreference: Pair<String, Int?>? = null
    private var published: CaptureInputState? = null

    val isFallback: Boolean get() = policy.isFallback

    fun attach(record: AudioRecord) {
        val owner = generation.incrementAndGet()
        routingListener = AudioRouting.OnRoutingChangedListener {
            if (generation.get() == owner) dirty.set(true)
        }.also { record.addOnRoutingChangedListener(it, Handler(Looper.getMainLooper())) }
        lastPreference = null
        actual = null
        policy.recorderReopened()
        refreshInventory()
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
            if (manager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                modeRequested = true
            }
            if (!shouldContinue()) return
            if (Build.VERSION.SDK_INT >= 31) {
                communicationListener = AudioManager.OnCommunicationDeviceChangedListener { dirty.set(true) }
                    .also { manager.addOnCommunicationDeviceChangedListener(Executor { it.run() }, it) }
                val device = target.communicationId?.let(communications::get)
                communicationRequested = device != null && manager.setCommunicationDevice(device)
                if (!communicationRequested) policy.fallback(InputFallbackReason.UNAVAILABLE)
            } else {
                scoReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        scoConnected = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
                        dirty.set(true)
                    }
                }.also {
                    val sticky = ContextCompat.registerReceiver(context, it,
                        IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
                    scoConnected = sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
                }
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
        if (dirty.getAndSet(false) || controller.state.value !== inventory) {
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
        if (count == AudioRecord.ERROR_DEAD_OBJECT) policy.fallback(InputFallbackReason.UNAVAILABLE)
        policy.observe(actual, targetAvailable, frames = count > 0, silenced = silenced)
        publish()
        // Never throw after a successful read: that PCM still needs to reach the
        // writer. A failed recovery is reported before the next native read.
    }

    fun takeReopenRequest(): Boolean = policy.takeReopenRequest()

    private fun refreshInventory() {
        controller.state.value?.let { inventory = it }
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
    }

    private fun refreshActual(record: AudioRecord) {
        val routed = record.routedDevice
        actual = when {
            routed == null -> null
            routed.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInput(AudioInput.PHONE_KEY, "", false, routed.id)
            else -> inventory.byInputId[routed.id] ?: AudioInput("actual:${routed.id}", routed.productName.toString(),
                AndroidAudioInputDevices.isBluetooth(routed.type), routed.id)
        }
    }

    private fun communicationReady(): Boolean {
        if (!communicationRequested) return false
        return if (Build.VERSION.SDK_INT >= 31) currentCommunicationId == target.communicationId
        else scoConnected
    }

    private fun prefer(record: AudioRecord, key: String) {
        val inputId = if (key == AudioInput.PHONE_KEY) phoneId
            else inventory.byKey[key]?.inputId ?: target.inputId
        val preference = key to inputId
        if (lastPreference == preference) return
        val device = inputId?.let(inputs::get)
        // A null Bluetooth source allows Android's established communication
        // route to pick its source; null must never mean "Phone microphone".
        if (key == AudioInput.PHONE_KEY && device == null) error(context.getString(io.github.lrq3000.utterlane.R.string.audio_input_phone_unavailable))
        if (key == target.key && target.bluetooth && Build.VERSION.SDK_INT < 31 && scoConnected && !scoRoutingSet) {
            manager.isBluetoothScoOn = true
            scoRoutingSet = true
        }
        val accepted = try { record.setPreferredDevice(device) }
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

    private fun publish() {
        if (published != policy.state) { published = policy.state; onState(policy.state) }
    }

    fun detach(record: AudioRecord) {
        generation.incrementAndGet()
        routingListener?.let { record.removeOnRoutingChangedListener(it) }
        routingListener = null
    }

    private fun releaseCommunication() {
        if (scoRoutingSet) {
            scoRoutingSet = false
            try { manager.isBluetoothScoOn = false }
            catch (e: RuntimeException) { Log.w(TAG, "Could not clear SCO routing", e) }
        }
        if (communicationRequested) {
            communicationRequested = false
            try {
                if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice() else manager.stopBluetoothSco()
            } catch (e: RuntimeException) { Log.w(TAG, "Could not release communication route", e) }
        }
        if (modeRequested) {
            modeRequested = false
            // MODE_NORMAL removes this app's mode claim; telephony/other clients
            // retain their own higher-priority claims in Android's audio service.
            try { manager.mode = AudioManager.MODE_NORMAL }
            catch (e: RuntimeException) { Log.w(TAG, "Could not release communication mode", e) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        releaseCommunication()
        if (Build.VERSION.SDK_INT >= 31) communicationListener?.let(manager::removeOnCommunicationDeviceChangedListener)
        communicationListener = null
        scoReceiver?.let(context::unregisterReceiver)
        scoReceiver = null
    }

    companion object { private const val TAG = "CaptureRoute" }
}
