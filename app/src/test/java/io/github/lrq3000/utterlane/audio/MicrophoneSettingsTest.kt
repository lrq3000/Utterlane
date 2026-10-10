package io.github.lrq3000.utterlane.audio

import io.github.lrq3000.utterlane.settings.MemoryStore
import io.github.lrq3000.utterlane.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class MicrophoneSettingsTest {
    @Test fun absentSettingsUseTheExactHfpPresetWithoutChangingInputPreference() = runBlocking {
        val settings = SettingsRepository(MemoryStore())
        settings.updateAudioInput { InputPreferences("headset", true) }
        assertEquals(MicrophonePreset.HFP_PRESET, settings.microphoneSettings.first().preset)
        val options = settings.microphoneSettings.first().options
        assertEquals(MicrophoneSource.VOICE_RECOGNITION, options.source)
        assertEquals(BluetoothCaptureRoute.HFP_VOICE_RECOGNITION, options.route)
        assertEquals(BluetoothAudioMode.NORMAL, options.mode)
        assertEquals(InputPreprocessingPolicy.AGC_ONLY, options.preprocessing)
        assertEquals(PcmGainMode.AUTO_LEVEL, options.gain)
        assertEquals(InputPreferences("headset", true), settings.audioInputPreferences.first())
    }

    @Test fun presetAndCustomEditsAreAtomicAndPreserveIndependentFields() = runBlocking {
        val store = MemoryStore()
        val settings = SettingsRepository(store)
        settings.setMicrophonePreset(MicrophonePreset.DISABLED)
        assertEquals(MicrophoneOptions.STANDARD, settings.microphoneSettings.first().options)
        settings.setMicrophonePreset(MicrophonePreset.HFP_PRESET)
        settings.setMicrophonePreset(MicrophonePreset.CUSTOM)
        assertEquals(MicrophoneOptions.HFP, settings.microphoneSettings.first().options)
        coroutineScope {
            launch { settings.updateMicrophoneOptions { it.copy(gain = PcmGainMode.DB_PLUS_6) } }
            launch { settings.updateMicrophoneOptions { it.copy(source = MicrophoneSource.MIC) } }
        }
        val saved = SettingsRepository(store).microphoneSettings.first()
        assertEquals(MicrophonePreset.CUSTOM, saved.preset)
        assertEquals(PcmGainMode.DB_PLUS_6, saved.options.gain)
        assertEquals(MicrophoneSource.MIC, saved.options.source)
        assertEquals(BluetoothCaptureRoute.HFP_VOICE_RECOGNITION, saved.options.route)
        settings.resetMicrophoneOptions()
        assertEquals(MicrophoneSettings(), settings.microphoneSettings.first())
    }

    @Test fun stalePresetLabelsAndUnknownEnumsCannotMisrepresentTheConfiguration() {
        val values = MicrophoneOptions.HFP.toMap() + mapOf("preset" to "HFP_PRESET", "gain" to "OFF")
        assertEquals(MicrophonePreset.CUSTOM, MicrophoneSettings.fromMap(values).preset)
        assertEquals(MicrophoneOptions.STANDARD,
            MicrophoneSettings.fromMap(mapOf("preset" to "DISABLED")).options)
        assertEquals(MicrophoneOptions.HFP,
            MicrophoneSettings.fromMap(mapOf("gain" to "future-gain")).options)
        assertEquals(MicrophonePreset.CUSTOM,
            MicrophoneSettings.fromMap(MicrophoneOptions.HFP.toMap() + ("preset" to "CUSTOM")).preset)
    }
}
