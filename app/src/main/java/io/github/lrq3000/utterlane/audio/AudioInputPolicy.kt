package io.github.lrq3000.utterlane.audio

/** A persistent identity is separate from Android's connection-local input/output port IDs. */
data class AudioInput(
    val key: String,
    val name: String,
    val bluetooth: Boolean,
    val inputId: Int? = null,
    val communicationId: Int? = null
) {
    val isPhone: Boolean get() = key == PHONE_KEY
    // Closing SCO can remove only its input port while the headset remains
    // connected. Modern selection belongs to the communication endpoint instead.
    val connectionId: Int? get() = communicationId ?: inputId
    companion object { const val PHONE_KEY = "phone" }
}

data class InputPreferences(val selectedKey: String = AudioInput.PHONE_KEY, val preferBluetooth: Boolean = false)

data class AudioInputState(val inputs: List<AudioInput>, val preferences: InputPreferences) {
    val byKey = inputs.associateBy { it.key }
    val byInputId = inputs.mapNotNull { input -> input.inputId?.let { it to input } }.toMap()
    val selected: AudioInput get() = byKey[preferences.selectedKey] ?: AudioInput(AudioInput.PHONE_KEY, "", false)
}

/** O(devices), run on inventory/settings changes, never per PCM block. */
class AudioInputPolicy {
    fun reconcile(saved: InputPreferences, inputs: List<AudioInput>): InputPreferences {
        val selected = inputs.firstOrNull { it.key == saved.selectedKey }
        val target = if (saved.preferBluetooth) {
            selected?.takeIf { it.bluetooth } ?: inputs.asSequence().filter { it.bluetooth }.minByOrNull { it.key } ?: selected
        } else selected
        return saved.copy(selectedKey = target?.key ?: AudioInput.PHONE_KEY)
    }

    fun select(saved: InputPreferences, input: AudioInput): InputPreferences =
        InputPreferences(input.key, saved.preferBluetooth && input.bluetooth)
}
