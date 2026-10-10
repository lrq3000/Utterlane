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
    private val useCommunicationDevice = target.bluetooth && options.route == BluetoothCaptureRoute.COMMUNICATION_DEVICE
    private val useStandardSco = target.bluetooth && options.route == BluetoothCaptureRoute.STANDARD_SCO
    private var routeProblem: String? = null
    private var selectedDevice: AudioDeviceInfo? = null
    private var standardPreferredInputId: Int? = null
    private class SourceBinding(val input: AudioInput) { var removed = false }
    private val bindingLock = Any()
    private var sourceBinding = target.takeIf { it.inputId != null }?.let(::SourceBinding)
    private val deviceVersion = AtomicLong()
    private var inventoryVersion = -1L
    private val policy = CaptureRoutePolicy(target, SystemClock::uptimeMillis, if (useHfp) 8000 else 5000)
    private var hfp: HfpMicrophoneRoute? = null
    private var hfpExactTarget = false
    private var hfpPreferredInputId: Int? = null
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
    private val routingVersion = AtomicLong()
    private val sourceVersion = AtomicLong()
    private data class ReadEvidence(val key: String?, val inputId: Int?, val mode: Int?, val routing: Long, val source: Long)
    private var readEvidence: ReadEvidence? = null
    private var lastReadEvidence: ReadEvidence? = null
    private var inputBufferFrames = 0L
    private var confirmationFrames = 0L
    private var awaitingVerifiedFrames = true
    private var publishedAwaitingFrames: Boolean? = null
    private var observedMode: Int? = null
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
            if (generation.get() == owner) { routingVersion.incrementAndGet(); dirty.set(true) }
        }
        routingListener = listener
        record.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
        lastPreference = null
        actual = null
        readEvidence = null
        lastReadEvidence = null
        inputBufferFrames = record.bufferSizeInFrames.toLong().coerceAtLeast(0)
        confirmationFrames = inputBufferFrames
        awaitingVerifiedFrames = true
        val rate = io.github.lrq3000.utterlane.asr.AudioRecorder.SAMPLE_RATE
        policy.recorderReopened((inputBufferFrames * 1000 + rate - 1) / rate)
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
            if (useCommunicationDevice && Build.VERSION.SDK_INT < 31) {
                routeProblem = "COMMUNICATION_DEVICE requires Android 12 or newer; no Bluetooth API substitution."
                policy.fallback(InputFallbackReason.UNSUPPORTED_ROUTE)
                return
            }
            if (manager.mode == AudioManager.MODE_IN_CALL) {
                policy.fallback(InputFallbackReason.UNAVAILABLE)
                return
            }
            if (useHfp) {
                val device = selectedDevice
                hfpExactTarget = Build.VERSION.SDK_INT >= 28 && !device?.address.isNullOrBlank()
                hfp = HfpMicrophoneRoute(context, device, shouldContinue, { dirty.set(true) }).also { it.start() }
                if (hfp?.status?.phase == HfpMicrophoneRoute.Phase.FAILED) {
                    policy.fallback(InputFallbackReason.UNAVAILABLE)
                    return
                }
            }
            // Route and mode are independent user choices. Android may refuse a
            // mode (for example while another app owns it); verify rather than
            // silently request a different mode to make this route succeed.
            if (manager.mode != options.mode.androidMode) {
                modeRequested = true
                manager.mode = options.mode.androidMode
            }
            if (!shouldContinue()) return
            if (useHfp) { dirty.set(true); return }
            if (useCommunicationDevice && Build.VERSION.SDK_INT >= 31) {
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
        if (dirty.getAndSet(false)) refreshInventory()
        // Routing callbacks can still be queued while another input is active.
        // Read actual native evidence at both boundaries of every PCM read.
        refreshActual(record)
        verifySelectedMode()
        policy.observe(actual, targetAvailable, frames = false, silenced = silenced)
        if (policy.isFallback) releaseCommunication()
        val key = when {
            policy.isFallback -> AudioInput.PHONE_KEY
            !target.bluetooth -> target.key
            communicationReady() -> target.key
            else -> AudioInput.PHONE_KEY
        }
        if (shouldContinue()) {
            prefer(record, key)
            if (actual == null) refreshActual(record)
        }
        readEvidence = currentReadEvidence()
        if (readEvidence != lastReadEvidence) confirmationFrames = inputBufferFrames
        lastReadEvidence = readEvidence
        publish()
        check(!policy.failed) { context.getString(if (target.isPhone)
            io.github.lrq3000.utterlane.R.string.audio_input_phone_not_applied
            else io.github.lrq3000.utterlane.R.string.audio_input_fallback_failed) }
    }

    fun afterRead(record: AudioRecord, count: Int, silenced: Boolean) {
        // Query after the first read as well: routedDevice is not valid before
        // recording starts, and a routing callback can arrive slightly later.
        try {
            if (dirty.getAndSet(false)) refreshInventory()
            refreshActual(record)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not confirm input route; requesting phone fallback", e)
            actual = null
            policy.fallback(InputFallbackReason.UNAVAILABLE)
        }
        if (targetRemoved.get()) policy.fallback(InputFallbackReason.DISCONNECTED)
        if (count == AudioRecord.ERROR_DEAD_OBJECT) policy.fallback(InputFallbackReason.UNAVAILABLE)
        verifySelectedMode()
        val transportReady = !target.bluetooth || policy.isFallback || communicationReady()
        val verified = verifyReadBoundary(count)
        awaitingVerifiedFrames = !verified
        policy.observe(actual, targetAvailable, frames = count > 0 && transportReady, silenced = silenced, verified = verified)
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
        if (!target.isPhone && !policy.isFallback) {
            val current = target.communicationId?.let(communications::get) ?: target.inputId?.let(inputs::get)
            val frozen = selectedDevice
            if (current == null || (frozen == null && !AndroidAudioInputDevices.matchesKnownKey(target.key, current)) ||
                (frozen != null && (frozen.type != current.type || contraryAddress(frozen, current)))) {
                routeProblem = "Selected microphone identity is unavailable or changed before activation; no device substitution."
                policy.fallback(InputFallbackReason.UNAVAILABLE)
            } else if (frozen == null) selectedDevice = current
        }
        inventoryVersion = version
        synchronized(bindingLock) {
            standardPreferredInputId = null
            if (useStandardSco && !policy.isFallback) {
                val bound = validSourceBindingLocked()?.inputId?.let(inputs::get)
                    ?.takeIf { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !contrarySelectedInput(it) }
                val mapped = inventory.byKey[target.key]?.inputId?.let(inputs::get)
                    ?.takeIf { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !contrarySelectedInput(it) }
                val address = selectedDevice?.takeIf { Build.VERSION.SDK_INT >= 28 }?.address?.takeIf { it.isNotBlank() }
                val exact = if (address == null) null else inputs.values.filter {
                    it.isSource && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.address.equals(address, true)
                }.singleOrNull()
                standardPreferredInputId = (bound ?: mapped ?: exact)?.id
            }
            hfpPreferredInputId = null
            if (useHfp && inventoryVersion == deviceVersion.get() && hfp?.status?.requestAccepted == true && !policy.isFallback) {
                // Filter once so addressless matching stays linear even when many
                // unrelated USB/phone ports precede the SCO candidates.
                val classic = inputs.values.filter { it.isSource && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                val matches = classic.filter { hfp?.matchInput(it, classic) == HfpMicrophoneRoute.InputMatch.MATCH }
                if (matches.any { hfpTargetDisproved(it.id) }) policy.fallback(InputFallbackReason.ROUTE_CHANGED)
                else hfpPreferredInputId = matches.singleOrNull()?.id
            }
        }
    }

    private fun refreshActual(record: AudioRecord) {
        if (inspectActual(record)) return
        // One device event may have raced the native query. Reconcile and re-read
        // before publishing missing evidence; unrelated hotplug must not erase
        // a healthy input's warm-up progress. Bound retries if inventory churns.
        refreshInventory()
        if (!inspectActual(record)) {
            actual = null
            dirty.set(true)
        }
    }

    private fun inspectActual(record: AudioRecord): Boolean {
        // Native queries stay outside the binding lock. A removal can arrive
        // during this query, so recheck the inventory generation while publishing
        // the binding under the same lock used by device callbacks.
        val routed = record.routedDevice?.takeIf { it.isSource }
        if (useCommunicationDevice && Build.VERSION.SDK_INT >= 31) currentCommunicationId = manager.communicationDevice?.id
        // SCO readiness can change on its callback thread after this query.
        // Sample mode independently so a newly ready link never compares against
        // a null value that merely meant "not sampled while connecting".
        observedMode = if (target.bluetooth && !policy.isFallback) runCatching { manager.mode }.getOrNull() else null
        synchronized(bindingLock) {
            if (inventoryVersion != deviceVersion.get()) {
                return false
            }
            val bound = validSourceBindingLocked()
            val contrary = routed != null && contrarySelectedInput(routed)
            if (contrary && !policy.isFallback) {
                sourceBinding?.takeIf { it.input.inputId == routed?.id }?.removed = true
                routeProblem = "Observed input does not match the selected microphone/transport; no device substitution."
                policy.fallback(InputFallbackReason.ROUTE_CHANGED)
            }
            val classicMatch = useStandardSco && !contrary && routed?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                (routed.id == standardPreferredInputId ||
                    (bound != null && routed.id == bound.inputId && inputs[bound.inputId]?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            val hfpMatch = if (useHfp && routed != null && !policy.isFallback)
                hfp?.matchInput(routed, inputs.values) else null
            if (hfpMatch == HfpMicrophoneRoute.InputMatch.MATCH && hfpTargetDisproved(routed?.id)) {
                sourceBinding?.takeIf { it.input.inputId == routed?.id }?.removed = true
                policy.fallback(InputFallbackReason.ROUTE_CHANGED)
            }
            if (hfpMatch == HfpMicrophoneRoute.InputMatch.DIFFERENT && routed != null &&
                AndroidAudioInputDevices.isBluetooth(routed.type)) {
                sourceBinding?.takeIf { it.input.inputId == routed.id }?.removed = true
                policy.fallback(InputFallbackReason.ROUTE_CHANGED)
            }
            actual = when {
                routed == null -> null
                routed.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInput(AudioInput.PHONE_KEY, "", false, routed.id)
                contrary -> AudioInput("actual:${routed.id}", routed.productName.toString(),
                    AndroidAudioInputDevices.isBluetooth(routed.type), routed.id)
                classicMatch && !policy.isFallback -> AudioInput(target.key, target.name, true, routed.id,
                    inventory.byInputId[routed.id]?.communicationId)
                useStandardSco && !policy.isFallback -> AudioInput("actual:${routed.id}", routed.productName.toString(),
                    AndroidAudioInputDevices.isBluetooth(routed.type), routed.id)
                hfpMatch == HfpMicrophoneRoute.InputMatch.MATCH && !policy.isFallback -> AudioInput(target.key,
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
        return true
    }

    private fun devicePortsChanged(removed: Array<out AudioDeviceInfo>) {
        synchronized(bindingLock) {
            val bound = sourceBinding
            val removedSource = bound != null && removed.any { it.id == bound.input.inputId || it.id == bound.input.connectionId }
            val removedTarget = removed.any { it.id == target.connectionId }
            if (removedSource) bound?.removed = true
            if (removedTarget) targetRemoved.set(true)
            // All device events invalidate catalogue publication, but unrelated
            // additions/removals must not endlessly restart a healthy mic's
            // client-buffer verification. Only this session's loss advances it.
            if (removedSource || removedTarget) sourceVersion.incrementAndGet()
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
            if (hfpTargetDisproved(bound.input.inputId)) policy.fallback(InputFallbackReason.ROUTE_CHANGED)
            return null
        }
        return bound.input
    }

    // An exact target/profile address can establish a BLE-to-classic transport
    // association. A sole-peer inference cannot override positive catalogue
    // evidence that its source belongs to a different selected endpoint.
    private fun hfpTargetDisproved(inputId: Int?): Boolean {
        if (!useHfp || hfp == null || hfpExactTarget) return false
        val mapped = inventory.byInputId[inputId] ?: return false
        return mapped.connectionId != target.connectionId
    }

    private fun communicationReady(): Boolean {
        if (useHfp) return hfp?.status?.phase == HfpMicrophoneRoute.Phase.READY
        if (!communicationRequested) return false
        return if (useCommunicationDevice) currentCommunicationId == target.communicationId
        else scoConnected
    }

    private fun verifySelectedMode() {
        if (!target.bluetooth || policy.isFallback || !communicationReady()) return
        val observed = observedMode
        if (observed != options.mode.androidMode) {
            routeProblem = "Android did not apply requested mode ${options.mode} (${options.mode.androidMode}); " +
                "observed mode=${observed ?: "unknown"}."
            policy.fallback(InputFallbackReason.MODE_NOT_APPLIED)
        }
    }

    private fun currentReadEvidence() = ReadEvidence(actual?.key, actual?.inputId,
        observedMode.takeIf { target.bluetooth && !policy.isFallback },
        routingVersion.get(), sourceVersion.get())

    private fun verifyReadBoundary(count: Int): Boolean {
        val before = readEvidence
        val after = currentReadEvidence()
        lastReadEvidence = after
        if (before?.inputId == null || before != after) {
            confirmationFrames = inputBufferFrames
            return false
        }
        // Native route changes can leave previous-input samples in the client
        // buffer. Retain all PCM, but do not confirm the new input/fallback until
        // a complete buffer capacity has passed through stable read boundaries.
        if (confirmationFrames > 0) {
            if (count > 0) confirmationFrames = (confirmationFrames - count).coerceAtLeast(0)
            return false
        }
        return true
    }

    private fun contrarySelectedInput(device: AudioDeviceInfo): Boolean {
        val selected = selectedDevice ?: return false
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return false // Declared startup/fallback capture.
        val expectedType = if (useHfp || useStandardSco) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else selected.type
        return device.type != expectedType || contraryAddress(selected, device)
    }

    private fun contraryAddress(first: AudioDeviceInfo, second: AudioDeviceInfo): Boolean =
        Build.VERSION.SDK_INT >= 28 && first.address.isNotBlank() && second.address.isNotBlank() &&
            !first.address.equals(second.address, true)

    private fun prefer(record: AudioRecord, key: String) {
        val inputId = synchronized(bindingLock) {
            if (inventoryVersion != deviceVersion.get()) { dirty.set(true); return }
            if (key == AudioInput.PHONE_KEY) phoneId
            else if (useHfp) {
                // Never pin the original BLE source when the requested transport
                // is classic HFP. A proven SCO binding or unique verified SCO port
                // wins; otherwise retain Phone until a concrete source is verified.
                validSourceBindingLocked()?.inputId?.takeIf { inputs[it]?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: hfpPreferredInputId
            } else if (useStandardSco) standardPreferredInputId
            else validSourceBindingLocked()?.inputId ?: inventory.byKey[key]?.inputId
        }
        val preference = key to inputId
        if (lastPreference == preference) return
        val device = inputId?.let(inputs::get)
        // Every actual preference request names a concrete source. In particular,
        // a missing Phone port cannot be represented by an automatic/null choice.
        if (key == AudioInput.PHONE_KEY && device == null) error(context.getString(io.github.lrq3000.utterlane.R.string.audio_input_phone_unavailable))
        // Do not clear the explicit Phone preference and ask Android to choose
        // an arbitrary source while the selected input port is still unknown.
        if (device == null) return
        if (key != AudioInput.PHONE_KEY && (contrarySelectedInput(device) ||
                (useHfp && hfp?.matchInput(device, inputs.values) == HfpMicrophoneRoute.InputMatch.DIFFERENT))) {
            routeProblem = "Selected input port no longer matches the requested microphone/transport."
            policy.fallback(InputFallbackReason.ROUTE_CHANGED)
            dirty.set(true)
            return
        }
        val accepted = try {
            if (key == target.key && target.bluetooth && !useCommunicationDevice && communicationReady() && !scoRoutingSet) {
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
        if (changed || publishedHfp != hfpState || publishedAwaitingFrames != awaitingVerifiedFrames || forceDiagnostic) {
            publishedHfp = hfpState
            publishedAwaitingFrames = awaitingVerifiedFrames
            val mode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
            val routing = if (!target.bluetooth) "Bluetooth route not requested for this input" else
                "Requested Bluetooth: ${options.route}; requested mode=${options.mode}; observed mode=$mode\n" +
                    (hfpState?.let { "HFP: ${it.phase}; request=${it.requestAccepted}; ${it.detail}" }
                        ?: if (useHfp) "HFP was not requested; input or capture availability prevented setup"
                         else "Explicit ${options.route} request; actual microphone confirmation is independent") +
                    (routeProblem?.let { "\n$it" } ?: "")
            val confirmation = if (awaitingVerifiedFrames) "Input/transition confirmation pending; captured PCM is retained."
                else "Input observed across stable read boundaries; captured PCM is retained."
            onDiagnostic((if (closed) "Capture route teardown attempted.\n" else "") + routing + "\n" + confirmation)
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
                if (useCommunicationDevice) {
                    if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
                } else manager.stopBluetoothSco()
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
