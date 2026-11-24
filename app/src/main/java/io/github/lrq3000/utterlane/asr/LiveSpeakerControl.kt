package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One recording's request. A tap wins over late publication of the default. */
class LiveSpeakerControl {
    private val requested = MutableStateFlow<Boolean?>(null)
    val state: StateFlow<Boolean?> = requested

    fun set(enabled: Boolean) { requested.value = enabled }
    internal fun initialize(default: Boolean) { requested.compareAndSet(null, default) }
    internal fun resolve(default: Boolean): Boolean = requested.value ?: default
    internal fun unavailable() { requested.compareAndSet(true, false) }
}
