package io.github.lrq3000.utterlane.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.asr.ModelIdleTimeout
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.audio.InputPreferences
import io.github.lrq3000.utterlane.audio.AudioInput
import io.github.lrq3000.utterlane.audio.MicrophoneSettings
import io.github.lrq3000.utterlane.audio.MicrophonePreset
import io.github.lrq3000.utterlane.audio.MicrophoneOptions

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    companion object {
        private val SERVICE_ENABLED_KEY = booleanPreferencesKey("service_enabled")
        private val AUDIO_INPUT_KEY = stringPreferencesKey("audio_input")
        private val PREFER_BLUETOOTH_KEY = booleanPreferencesKey("prefer_bluetooth_microphone")
        private val MICROPHONE_KEYS = (MicrophoneOptions.HFP.toMap().keys + "preset")
            .associateWith { stringPreferencesKey("microphone_$it") }
        private val THEME_KEY = stringPreferencesKey("theme_mode")
        private val SHOW_TRANSCRIPTION_STREAM_STATISTICS_KEY = booleanPreferencesKey("show_transcription_stream_statistics")
        private val VISUAL_REFRESH_RATE_KEY = intPreferencesKey("visual_refresh_rate")
        val VISUAL_REFRESH_RATES = VisualRefreshRate.supported
        private val BUTTON_X_KEY = intPreferencesKey("button_x")
        private val BUTTON_Y_KEY = intPreferencesKey("button_y")
        private val AUTO_LOAD_MODEL_KEY = booleanPreferencesKey("auto_load_model")
        private val MODEL_IDLE_TIMEOUT_KEY = stringPreferencesKey("model_idle_timeout")
        private val DICTIONARY_ENABLED_KEY = booleanPreferencesKey("dictionary_enabled")
        private val AUDIO_MONITOR_ENABLED_KEY = booleanPreferencesKey("audio_monitor_enabled")
        private val MONITORED_FOLDERS_KEY = stringSetPreferencesKey("monitored_folders")
        private val FLOATING_BUTTON_SIZE_KEY = stringPreferencesKey("floating_button_size")
        private val FLOATING_BUTTON_SIZE_DP_KEY = intPreferencesKey("floating_button_size_dp")
        private val HISTORY_RETENTION_KEY = stringPreferencesKey("history_retention")
        private val AUDIO_HISTORY_ENABLED_KEY = booleanPreferencesKey("audio_history_enabled")
        private val AUDIO_HISTORY_RETENTION_KEY = stringPreferencesKey("audio_history_retention")
        private val TRANSCRIPT_HISTORY_ENABLED_KEY = booleanPreferencesKey("transcript_history_enabled")
        private val TRANSCRIPT_HISTORY_RETENTION_KEY = stringPreferencesKey("transcript_history_retention")
        private val SELECTED_MODEL_KEY = stringPreferencesKey("selected_model")
        private val DIARIZATION_KEY = booleanPreferencesKey("speaker_diarization")
        private val SPEAKER_COUNT_KEY = intPreferencesKey("speaker_count")
        private val RUNTIME_KEYS = RuntimeOptions().toMap().keys.associateWith { stringPreferencesKey("runtime_$it") }
        private val RUNTIME_GROUP_KEYS = RuntimeOptions.fields.groupBy { it.group }
            .mapValues { (_, fields) -> fields.map { it.key }.toSet() }

        const val BUTTON_SIZE_SMALL = "small"   // 44dp
        const val BUTTON_SIZE_MEDIUM = "medium" // 56dp (default)
        const val BUTTON_SIZE_LARGE = "large"   // 72dp
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    // One DataStore transaction publishes a complete, validated snapshot. Reading raw
    // values also tolerates a wrongly typed preference left by a corrupt/older build.
    val runtimeOptions: Flow<RuntimeOptions> = dataStore.data.map { readRuntimeOptions(it) }

    private fun readAudioInput(preferences: Preferences) = InputPreferences(
        preferences[AUDIO_INPUT_KEY] ?: AudioInput.PHONE_KEY, preferences[PREFER_BLUETOOTH_KEY] ?: false)

    val audioInputPreferences: Flow<InputPreferences> = dataStore.data.map(::readAudioInput)
    private fun readMicrophone(preferences: Preferences): MicrophoneSettings {
        val raw = preferences.asMap()
        return MicrophoneSettings.fromMap(MICROPHONE_KEYS.mapNotNull { (name, key) ->
            (raw[key] as? String)?.let { name to it }
        }.toMap())
    }
    val microphoneSettings: Flow<MicrophoneSettings> = dataStore.data.map(::readMicrophone)

    private fun writeMicrophone(preferences: MutablePreferences, preset: MicrophonePreset, options: MicrophoneOptions) {
        (options.toMap() + ("preset" to preset.name)).forEach { (name, value) -> preferences[MICROPHONE_KEYS.getValue(name)] = value }
    }

    suspend fun setMicrophonePreset(preset: MicrophonePreset) {
        dataStore.edit { preferences ->
            val options = when (preset) {
                MicrophonePreset.HFP_PRESET -> MicrophoneOptions.HFP
                MicrophonePreset.DISABLED -> MicrophoneOptions.STANDARD
                MicrophonePreset.CUSTOM -> readMicrophone(preferences).options
            }
            writeMicrophone(preferences, preset, options)
        }
    }

    suspend fun updateMicrophoneOptions(change: (MicrophoneOptions) -> MicrophoneOptions) {
        dataStore.edit { preferences ->
            val previous = readMicrophone(preferences)
            val next = change(previous.options)
            writeMicrophone(preferences, if (next == previous.options) previous.preset else MicrophonePreset.CUSTOM, next)
        }
    }

    suspend fun resetMicrophoneOptions() = setMicrophonePreset(MicrophonePreset.HFP_PRESET)

    /** The transform sees the latest values inside the transaction, including manual overrides. */
    suspend fun updateAudioInput(transform: (InputPreferences) -> InputPreferences): InputPreferences {
        val result = dataStore.edit { preferences ->
            val previous = readAudioInput(preferences)
            val next = transform(previous)
            // Avoid creating saved settings merely by inspecting a new installation.
            if (next != previous) {
                preferences[AUDIO_INPUT_KEY] = next.selectedKey
                preferences[PREFER_BLUETOOTH_KEY] = next.preferBluetooth
            }
        }
        return readAudioInput(result)
    }

    private fun readRuntimeOptions(preferences: Preferences): RuntimeOptions {
        val raw = preferences.asMap()
        return RuntimeOptions.fromMap(RUNTIME_KEYS.mapNotNull { (name, key) ->
            (raw[key] as? String)?.let { name to it }
        }.toMap())
    }

    suspend fun setRuntimeOptions(options: RuntimeOptions) {
        val values = options.requireValid().toMap()
        dataStore.edit { writeRuntimeOptions(it, values) }
    }

    private fun writeRuntimeOptions(preferences: MutablePreferences, values: Map<String, String>) {
        values.forEach { (name, value) -> preferences[RUNTIME_KEYS.getValue(name)] = value }
    }

    suspend fun resetRuntimeOptions() {
        dataStore.edit { preferences -> RUNTIME_KEYS.values.forEach { preferences.remove(it) } }
    }

    suspend fun updateRuntimeGroup(group: RuntimeOptionGroup, draftValues: Map<String, String>) {
        // Freeze the caller's draft before suspension; it can only affect its own group.
        val draft = draftValues.toMap()
        val allowed = RUNTIME_GROUP_KEYS.getValue(group)
        require(draft.keys.all { it in allowed }) { "Draft contains keys outside runtime group $group" }
        dataStore.edit { preferences ->
            // Read, merge, validate and write under the same DataStore transaction.
            // A prior Flow.first() could race with another independent group edit.
            val latest = readRuntimeOptions(preferences).toMap()
            val result = RuntimeOptions.parseDraft(latest + draft)
            val options = requireNotNull(result.options) { result.errors.values.distinct().joinToString("; ") }
            writeRuntimeOptions(preferences, options.toMap())
        }
    }

    val serviceEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SERVICE_ENABLED_KEY] ?: false
    }

    // Classify an installation only before onboarding initializes its own store.
    // Retain the injected DataStore so this also observes advanced-option edits.
    val hasSavedSettings: Flow<Boolean> = dataStore.data.map { it.asMap().isNotEmpty() }

    // Legacy No history meant automatic saving off, not immediate expiry of a
    // manually saved item. Read the old key as a fallback without rewriting it.
    val audioHistoryEnabled: Flow<Boolean> = dataStore.data.map {
        it[AUDIO_HISTORY_ENABLED_KEY] ?: (HistoryRetention.fromKey(it[HISTORY_RETENTION_KEY]) != HistoryRetention.NONE)
    }
    val audioHistoryRetention: Flow<HistoryRetention> = dataStore.data.map {
        it[AUDIO_HISTORY_RETENTION_KEY]?.let(HistoryRetention::fromKey)
            ?: HistoryRetention.fromKey(it[HISTORY_RETENTION_KEY]).takeUnless { value -> value == HistoryRetention.NONE }
            ?: HistoryRetention.HOUR
    }
    val transcriptHistoryEnabled: Flow<Boolean> = dataStore.data.map { it[TRANSCRIPT_HISTORY_ENABLED_KEY] ?: true }
    val transcriptHistoryRetention: Flow<HistoryRetention> = dataStore.data.map {
        it[TRANSCRIPT_HISTORY_RETENTION_KEY]?.let(HistoryRetention::fromKey) ?: HistoryRetention.DAY
    }
    suspend fun setAudioHistoryEnabled(value: Boolean) { dataStore.edit { it[AUDIO_HISTORY_ENABLED_KEY] = value } }
    suspend fun setAudioHistoryRetention(value: HistoryRetention) { dataStore.edit { it[AUDIO_HISTORY_RETENTION_KEY] = value.key } }
    suspend fun setTranscriptHistoryEnabled(value: Boolean) { dataStore.edit { it[TRANSCRIPT_HISTORY_ENABLED_KEY] = value } }
    suspend fun setTranscriptHistoryRetention(value: HistoryRetention) { dataStore.edit { it[TRANSCRIPT_HISTORY_RETENTION_KEY] = value.key } }

    /** Compatibility for older integrations; new UI uses independent preferences. */
    val historyRetention: Flow<HistoryRetention> = combine(audioHistoryEnabled, audioHistoryRetention) { enabled, duration ->
        if (enabled) duration else HistoryRetention.NONE
    }

    val selectedModelId: Flow<String> = dataStore.data.map { it[SELECTED_MODEL_KEY] ?: ModelCatalog.DEFAULT.id }
    val diarizationEnabled: Flow<Boolean> = dataStore.data.map { it[DIARIZATION_KEY] ?: false }
    val speakerCount: Flow<Int> = dataStore.data.map { (it[SPEAKER_COUNT_KEY] ?: 0).takeIf { n -> n in 0..8 } ?: 0 }
    suspend fun setDiarizationEnabled(enabled: Boolean) { dataStore.edit { it[DIARIZATION_KEY] = enabled } }
    suspend fun setSpeakerCount(count: Int) { require(count in 0..8); dataStore.edit { it[SPEAKER_COUNT_KEY] = count } }
    suspend fun setSelectedModelId(id: String) { dataStore.edit { it[SELECTED_MODEL_KEY] = id } }

    suspend fun setHistoryRetention(retention: HistoryRetention) {
        dataStore.edit {
            it[AUDIO_HISTORY_ENABLED_KEY] = retention != HistoryRetention.NONE
            if (retention != HistoryRetention.NONE) it[AUDIO_HISTORY_RETENTION_KEY] = retention.key
        }
    }

    val themeMode: Flow<String> = dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: THEME_SYSTEM
    }

    // Presentation is independent of operation snapshots and diagnostic logging consent.
    // A missing key also keeps upgraded installations on the simpler recording UI.
    val showTranscriptionStreamStatistics: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SHOW_TRANSCRIPTION_STREAM_STATISTICS_KEY] ?: false
    }

    suspend fun setShowTranscriptionStreamStatistics(show: Boolean) {
        dataStore.edit { it[SHOW_TRANSCRIPTION_STREAM_STATISTICS_KEY] = show }
    }

    val visualRefreshRate: Flow<Int> = dataStore.data.map {
        VisualRefreshRate.fromStored(it.asMap()[VISUAL_REFRESH_RATE_KEY] as? Int)
    }

    suspend fun setVisualRefreshRate(hz: Int) {
        VisualRefreshRate.requireValid(hz)
        dataStore.edit { it[VISUAL_REFRESH_RATE_KEY] = hz }
    }

    val buttonPosition: Flow<Pair<Int, Int>> = dataStore.data.map { preferences ->
        val x = preferences[BUTTON_X_KEY] ?: -1
        val y = preferences[BUTTON_Y_KEY] ?: -1
        Pair(x, y)
    }

    val autoLoadModel: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[AUTO_LOAD_MODEL_KEY] ?: false
    }

    val modelIdleTimeout: Flow<ModelIdleTimeout> = dataStore.data.map { preferences ->
        ModelIdleTimeout.fromKey(preferences[MODEL_IDLE_TIMEOUT_KEY])
    }

    suspend fun setModelIdleTimeout(timeout: ModelIdleTimeout) {
        dataStore.edit { it[MODEL_IDLE_TIMEOUT_KEY] = timeout.key }
    }

    val dictionaryEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[DICTIONARY_ENABLED_KEY] ?: true
    }

    val audioMonitorEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[AUDIO_MONITOR_ENABLED_KEY] ?: false
    }

    val monitoredFolders: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[MONITORED_FOLDERS_KEY] ?: emptySet()
    }

    private fun readFloatingButtonSizeDp(preferences: Preferences): Int {
        val raw = preferences.asMap()
        return FloatingButtonSize.diameter(raw[FLOATING_BUTTON_SIZE_KEY] as? String, raw[FLOATING_BUTTON_SIZE_DP_KEY] as? Int)
    }

    val floatingButtonSizeDp: Flow<Int> = dataStore.data.map(::readFloatingButtonSizeDp)
    val floatingButtonSize: Flow<String> = floatingButtonSizeDp.map { FloatingButtonSize.preset(it) ?: "custom" }

    suspend fun setFloatingButtonSize(size: String) {
        require(size in setOf(BUTTON_SIZE_SMALL, BUTTON_SIZE_MEDIUM, BUTTON_SIZE_LARGE))
        dataStore.edit { preferences ->
            preferences[FLOATING_BUTTON_SIZE_KEY] = size
            preferences.remove(FLOATING_BUTTON_SIZE_DP_KEY)
        }
    }

    suspend fun setFloatingButtonSizeDp(dp: Int) {
        val bounded = FloatingButtonSize.bounded(dp)
        dataStore.edit { preferences ->
            // Publish mode and size together; a collector must never see a stale
            // custom diameter paired with a newly selected preset (or vice versa).
            preferences[FLOATING_BUTTON_SIZE_KEY] = FloatingButtonSize.preset(bounded) ?: "custom"
            preferences[FLOATING_BUTTON_SIZE_DP_KEY] = bounded
        }
    }

    suspend fun setDictionaryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[DICTIONARY_ENABLED_KEY] = enabled
        }
    }

    suspend fun setAudioMonitorEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUDIO_MONITOR_ENABLED_KEY] = enabled
        }
    }

    suspend fun setMonitoredFolders(folders: Set<String>) {
        dataStore.edit { preferences ->
            preferences[MONITORED_FOLDERS_KEY] = folders
        }
    }

    suspend fun setAutoLoadModel(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_LOAD_MODEL_KEY] = enabled
        }
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SERVICE_ENABLED_KEY] = enabled
        }
    }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[THEME_KEY] = mode
        }
    }

    suspend fun setButtonPosition(x: Int, y: Int) {
        dataStore.edit { preferences ->
            preferences[BUTTON_X_KEY] = x
            preferences[BUTTON_Y_KEY] = y
        }
    }
}
