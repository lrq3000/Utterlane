package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SpeakerWarning { MODEL_NEEDED, PROCESSING_FAILED }

/** One recording's request. A tap wins over late publication of the default. */
class LiveSpeakerControl {
    private val requested = MutableStateFlow<Boolean?>(null)
    private val preference = MutableStateFlow<Boolean?>(null)
    val retryPreference: StateFlow<Boolean?> = preference
    private val settingsAfterDelivery = java.util.concurrent.atomic.AtomicBoolean()
    val state: StateFlow<Boolean?> = requested
    val settingsRequested get() = settingsAfterDelivery.get()

    @Synchronized fun set(enabled: Boolean) { preference.value = enabled; requested.value = enabled }
    fun requestSettingsAfterDelivery() = settingsAfterDelivery.compareAndSet(false, true)
    internal fun takeSettingsRequest() = settingsAfterDelivery.getAndSet(false)
    @Synchronized internal fun initialize(default: Boolean) { preference.compareAndSet(null, default); requested.compareAndSet(null, default) }
    internal fun resolve(default: Boolean): Boolean = requested.value ?: default
    internal fun unavailable() { requested.compareAndSet(true, false) }
}
