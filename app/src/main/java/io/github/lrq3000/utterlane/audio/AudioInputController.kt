package io.github.lrq3000.utterlane.audio

import io.github.lrq3000.utterlane.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

interface AudioInputDevices : Closeable {
    fun inputs(): List<AudioInput>
    fun observe(onChanged: (removedPorts: Set<Int>) -> Unit)
}

/** Application-owned next-recording selection. It never changes an active recorder. */
class AudioInputController(
    private val devices: AudioInputDevices,
    private val settings: SettingsRepository,
    parentScope: CoroutineScope,
    private val onError: (Exception) -> Unit = {}
) : Closeable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val policy = AudioInputPolicy()
    private val mutable = MutableStateFlow<AudioInputState?>(null)
    val state: StateFlow<AudioInputState?> = mutable.asStateFlow()
    private class WatchedInput(val input: AudioInput) {
        val removed = AtomicBoolean(false)
        fun markRemoved(ports: Set<Int>) { if (input.connectionId in ports) removed.set(true) }
    }
    private data class SelectionWatches(val selected: WatchedInput? = null, val pending: WatchedInput? = null)
    @Volatile private var watches = SelectionWatches()

    init {
        devices.observe { removedPorts ->
            // Latch only the selected/pending connections before conflating work.
            // Capturing this generation's object prevents an in-flight old event
            // from marking a newer manual selection removed. Memory stays O(1).
            val current = watches
            current.selected?.markRemoved(removedPorts)
            current.pending?.markRemoved(removedPorts)
            changes.trySend(Unit)
        }
        scope.launch {
            settings.audioInputPreferences.distinctUntilChanged().collect { changes.trySend(Unit) }
        }
        scope.launch {
            for (ignored in changes) {
                try { refresh() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { onError(e) }
            }
        }
        changes.trySend(Unit)
    }

    suspend fun refresh(): AudioInputState = mutex.withLock { update() }

    // Refresh under the same lock as picker actions, using the current inventory,
    // not a queued add/remove delta which may already have become obsolete.
    suspend fun snapshotForRecording(): AudioInputState = refresh()

    /** Live routing evidence must not wait for an unrelated suspended settings write. */
    internal fun currentInputs(): List<AudioInput> = devices.inputs()

    suspend fun select(key: String): Boolean = mutex.withLock {
        val inputs = devices.inputs()
        val input = inputs.firstOrNull { it.key == key } ?: return@withLock false
        // Watch the candidate before suspending for persistence. A disconnect
        // during that write belongs to this choice, unlike an earlier queued loss.
        // Retain the previous watch too so a failed write can safely keep it.
        val candidate = WatchedInput(input)
        watches = watches.copy(pending = candidate)
        try {
            val preferences = settings.updateAudioInput { policy.select(it, input) }
            watches = SelectionWatches(selected = candidate)
            publish(inputs, preferences)
            true
        } finally {
            if (watches.pending === candidate) watches = watches.copy(pending = null)
        }
    }

    suspend fun setPreferBluetooth(enabled: Boolean) = mutex.withLock {
        update { it.copy(preferBluetooth = enabled) }
    }

    private suspend fun update(transform: (InputPreferences) -> InputPreferences = { it }): AudioInputState {
        val inputs = devices.inputs()
        val previous = watches.selected
        var lossAcknowledged = false
        val preferences = settings.updateAudioInput { saved ->
            val current = inputs.firstOrNull { it.key == saved.selectedKey }
            lossAcknowledged = previous != null && previous.input.key == saved.selectedKey &&
                (previous.removed.get() || previous.input.connectionId != current?.connectionId)
            // Same durable identity with a different connection port is a reconnect
            // even if Android has not delivered its removal callback yet.
            val available = if (lossAcknowledged) saved.copy(selectedKey = AudioInput.PHONE_KEY) else saved
            policy.reconcile(transform(available), inputs)
        }
        return publish(inputs, preferences, resetWatch = lossAcknowledged)
    }

    private fun publish(inputs: List<AudioInput>, preferences: InputPreferences, resetWatch: Boolean = false): AudioInputState {
        val next = AudioInputState(inputs.toList(), preferences)
        val current = watches.selected
        if (resetWatch || current == null || current.input.key != next.selected.key ||
            current.input.connectionId != next.selected.connectionId) watches = SelectionWatches(WatchedInput(next.selected))
        // Preserve the existing watch otherwise: a removal arriving during a
        // suspended settings write must still be consumed by the queued refresh.
        mutable.value = next
        return next
    }

    override fun close() { devices.close(); changes.close(); scope.cancel() }
}
