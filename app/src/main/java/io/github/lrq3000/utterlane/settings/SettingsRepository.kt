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
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.asr.ModelIdleTimeout
import io.github.lrq3000.utterlane.history.HistoryRetention

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    companion object {
        private val SERVICE_ENABLED_KEY = booleanPreferencesKey("service_enabled")
        private val THEME_KEY = stringPreferencesKey("theme_mode")
        private val SHOW_TRANSCRIPTION_STREAM_STATISTICS_KEY = booleanPreferencesKey("show_transcription_stream_statistics")
        private val VISUAL_REFRESH_RATE_KEY = intPreferencesKey("visual_refresh_rate")
        val VISUAL_REFRESH_RATES = listOf(1, 2, 5, 10, 20)
        private val BUTTON_X_KEY = intPreferencesKey("button_x")
        private val BUTTON_Y_KEY = intPreferencesKey("button_y")
        private val AUTO_LOAD_MODEL_KEY = booleanPreferencesKey("auto_load_model")
        private val MODEL_IDLE_TIMEOUT_KEY = stringPreferencesKey("model_idle_timeout")
        private val DICTIONARY_ENABLED_KEY = booleanPreferencesKey("dictionary_enabled")
        private val AUDIO_MONITOR_ENABLED_KEY = booleanPreferencesKey("audio_monitor_enabled")
        private val MONITORED_FOLDERS_KEY = stringSetPreferencesKey("monitored_folders")
        private val FLOATING_BUTTON_SIZE_KEY = stringPreferencesKey("floating_button_size")
        private val HISTORY_RETENTION_KEY = stringPreferencesKey("history_retention")
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

    val historyRetention: Flow<HistoryRetention> = dataStore.data.map { preferences ->
        HistoryRetention.fromKey(preferences[HISTORY_RETENTION_KEY])
    }

    val selectedModelId: Flow<String> = dataStore.data.map { it[SELECTED_MODEL_KEY] ?: ModelCatalog.DEFAULT.id }
    val diarizationEnabled: Flow<Boolean> = dataStore.data.map { it[DIARIZATION_KEY] ?: false }
    val speakerCount: Flow<Int> = dataStore.data.map { (it[SPEAKER_COUNT_KEY] ?: 0).takeIf { n -> n in 0..8 } ?: 0 }
    suspend fun setDiarizationEnabled(enabled: Boolean) { dataStore.edit { it[DIARIZATION_KEY] = enabled } }
    suspend fun setSpeakerCount(count: Int) { require(count in 0..8); dataStore.edit { it[SPEAKER_COUNT_KEY] = count } }
    suspend fun setSelectedModelId(id: String) { dataStore.edit { it[SELECTED_MODEL_KEY] = id } }

    suspend fun setHistoryRetention(retention: HistoryRetention) {
        dataStore.edit { it[HISTORY_RETENTION_KEY] = retention.key }
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
        (it.asMap()[VISUAL_REFRESH_RATE_KEY] as? Int)?.takeIf { rate -> rate in VISUAL_REFRESH_RATES } ?: 10
    }

    suspend fun setVisualRefreshRate(hz: Int) {
        require(hz in VISUAL_REFRESH_RATES)
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

    val floatingButtonSize: Flow<String> = dataStore.data.map { preferences ->
        preferences[FLOATING_BUTTON_SIZE_KEY] ?: BUTTON_SIZE_MEDIUM
    }

    suspend fun setFloatingButtonSize(size: String) {
        dataStore.edit { preferences ->
            preferences[FLOATING_BUTTON_SIZE_KEY] = size
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
