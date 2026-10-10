package io.github.lrq3000.utterlane.audio

import android.media.MediaRecorder
import android.media.AudioManager

/** Stable enum names are persisted; Android source numbers are used only at the platform boundary. */
enum class MicrophoneSource(val androidSource: Int) {
    DEFAULT(MediaRecorder.AudioSource.DEFAULT), MIC(MediaRecorder.AudioSource.MIC),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION), UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED)
}
enum class BluetoothCaptureRoute { STANDARD_SCO, HFP_VOICE_RECOGNITION, COMMUNICATION_DEVICE }
enum class BluetoothAudioMode(val androidMode: Int) {
    IN_COMMUNICATION(AudioManager.MODE_IN_COMMUNICATION), NORMAL(AudioManager.MODE_NORMAL)
}
enum class InputPreprocessingPolicy(val ns: Boolean?, val aec: Boolean?, val agc: Boolean?) {
    SYSTEM_DEFAULT(null, null, null), DISABLE_NS(false, null, null),
    DISABLE_NS_AEC(false, false, null), DISABLE_NS_AEC_AGC(false, false, false), AGC_ONLY(false, false, true)
}
enum class PcmGainMode { OFF, DB_PLUS_6, DB_PLUS_12, DB_PLUS_18, AUTO_LEVEL }
enum class MicrophonePreset { DISABLED, HFP_PRESET, CUSTOM }

data class MicrophoneOptions(
    val source: MicrophoneSource = MicrophoneSource.VOICE_RECOGNITION,
    val route: BluetoothCaptureRoute = BluetoothCaptureRoute.HFP_VOICE_RECOGNITION,
    val mode: BluetoothAudioMode = BluetoothAudioMode.NORMAL,
    val preprocessing: InputPreprocessingPolicy = InputPreprocessingPolicy.AGC_ONLY,
    val gain: PcmGainMode = PcmGainMode.AUTO_LEVEL
) {
    fun toMap(): Map<String, String> = mapOf("source" to source.name, "route" to route.name,
        "mode" to mode.name, "preprocessing" to preprocessing.name, "gain" to gain.name)
    companion object {
        val HFP = MicrophoneOptions()
        val STANDARD = MicrophoneOptions(MicrophoneSource.DEFAULT, BluetoothCaptureRoute.STANDARD_SCO,
            BluetoothAudioMode.IN_COMMUNICATION, InputPreprocessingPolicy.SYSTEM_DEFAULT, PcmGainMode.OFF)
        fun fromMap(values: Map<String, String>, fallback: MicrophoneOptions = HFP) = MicrophoneOptions(
            enumValue(values["source"], fallback.source), enumValue(values["route"], fallback.route),
            enumValue(values["mode"], fallback.mode), enumValue(values["preprocessing"], fallback.preprocessing),
            enumValue(values["gain"], fallback.gain))
    }
}

data class MicrophoneSettings(val preset: MicrophonePreset = MicrophonePreset.HFP_PRESET,
    val options: MicrophoneOptions = MicrophoneOptions.HFP) {
    companion object {
        fun fromMap(values: Map<String, String>): MicrophoneSettings {
            val stored = values["preset"]?.let { name -> MicrophonePreset.entries.firstOrNull { it.name == name } }
            val options = MicrophoneOptions.fromMap(values,
                if (stored == MicrophonePreset.DISABLED) MicrophoneOptions.STANDARD else MicrophoneOptions.HFP)
            val inferred = when (options) {
                MicrophoneOptions.HFP -> MicrophonePreset.HFP_PRESET
                MicrophoneOptions.STANDARD -> MicrophonePreset.DISABLED
                else -> MicrophonePreset.CUSTOM
            }
            // Explicit Custom survives even if its values happen to equal a preset.
            // A stale fixed label must never promise an unapplied configuration.
            return MicrophoneSettings(if (stored == null || stored == inferred) inferred else MicrophonePreset.CUSTOM, options)
        }
    }
}

private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback
