package io.github.lrq3000.utterlane.audio

import io.github.lrq3000.utterlane.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

interface AudioInputDevices : Closeable {
    fun inputs(): List<AudioInput>
    fun observe(onChanged: () -> Unit)
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

    init {
        devices.observe { changes.trySend(Unit) }
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

    suspend fun select(key: String): Boolean = mutex.withLock {
        val inputs = devices.inputs()
        val input = inputs.firstOrNull { it.key == key } ?: return@withLock false
        publish(inputs, settings.updateAudioInput { policy.select(it, input) })
        true
    }

    suspend fun setPreferBluetooth(enabled: Boolean) = mutex.withLock {
        update { it.copy(preferBluetooth = enabled) }
    }

    private suspend fun update(transform: (InputPreferences) -> InputPreferences = { it }): AudioInputState {
        val inputs = devices.inputs()
        val preferences = settings.updateAudioInput { policy.reconcile(transform(it), inputs) }
        return publish(inputs, preferences)
    }

    private fun publish(inputs: List<AudioInput>, preferences: InputPreferences) =
        AudioInputState(inputs.toList(), preferences).also { mutable.value = it }

    override fun close() { devices.close(); changes.close(); scope.cancel() }
}
