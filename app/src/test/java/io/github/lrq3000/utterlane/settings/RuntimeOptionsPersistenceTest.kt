package io.github.lrq3000.utterlane.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RuntimeOptionsPersistenceTest {
    @Test fun visualFrequencyPersistsIndependentlyOfRecognitionAndDiagnostics() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        assertEquals(10, repository.visualRefreshRate.first())
        for (rate in listOf(1, 2, 5, 10, 20)) {
            repository.setVisualRefreshRate(rate)
            assertEquals(rate, SettingsRepository(store).visualRefreshRate.first())
        }
        try { repository.setVisualRefreshRate(60); fail("Invalid rate accepted") } catch (_: IllegalArgumentException) { }
        repository.resetRuntimeOptions()
        assertEquals(20, repository.visualRefreshRate.first())
        assertEquals(RuntimeOptions(), repository.runtimeOptions.first())
        store.edit { it[intPreferencesKey("visual_refresh_rate")] = -1 }
        assertEquals(10, repository.visualRefreshRate.first())
    }
    // DataStore 1.0 uses File.renameTo to replace files, which is not portable to
    // Windows JVM tests. Exercise repository transactions against an in-memory store;
    // Android still owns the real on-disk DataStore and its atomic file replacement.
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        var beforeUpdate: suspend () -> Unit = {}
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            beforeUpdate()
            return mutex.withLock { transform(data.value).also { data.value = it } }
        }
    }

    @Test fun snapshotWritesAreAtomicAndResetKeepsOtherPreferences() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
            repository.setThemeMode(SettingsRepository.THEME_DARK)
            assertEquals(RuntimeOptions(), repository.runtimeOptions.first())
             val policy = mapOf("strong_speaker_threshold" to "0.8", "strong_speaker_margin" to "0.25",
                 "strong_confirmation_ms" to "50", "word_fallback_ms" to "800")
             val options = RuntimeOptions.fromMap(RuntimeOptions(nativeFifoFrames = 32, nativeUpdateFrames = 32, diagnostics = true).toMap() + policy)
             repository.setRuntimeOptions(options)
             assertEquals(options, repository.runtimeOptions.first())
             assertEquals(policy, repository.runtimeOptions.first().toMap().filterKeys { it in policy })
            try {
                repository.setRuntimeOptions(options.copy(nativeUpdateFrames = 33))
                fail("An invalid snapshot must not be written")
            } catch (_: IllegalArgumentException) { }
            assertEquals(options, repository.runtimeOptions.first())
            repository.resetRuntimeOptions()
            assertEquals(RuntimeOptions(), repository.runtimeOptions.first())
            assertEquals(SettingsRepository.THEME_DARK, repository.themeMode.first())
            store.edit { it[stringPreferencesKey("runtime_asr_threads")] = "broken" }
            assertEquals(RuntimeOptions(), repository.runtimeOptions.first())
            store.edit { it[intPreferencesKey("runtime_asr_threads")] = 8 }
            assertEquals(RuntimeOptions(), repository.runtimeOptions.first())
    }

    @Test fun onboardingDetectsExistingAdvancedSettingsWithoutChangingThem() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        assertFalse(repository.hasSavedSettings.first())
        val options = RuntimeOptions(asrThreads = 8, diagnostics = true)
        repository.setRuntimeOptions(options)
        assertTrue(SettingsRepository(store).hasSavedSettings.first())
        repository.setThemeMode(SettingsRepository.THEME_DARK)
        repository.setDiarizationEnabled(true)
        assertEquals(options, repository.runtimeOptions.first())
        assertFalse(repository.showTranscriptionStreamStatistics.first())
    }

    @Test fun overlappingIndependentGroupsMergeInsideTheTransaction() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        repository.setThemeMode(SettingsRepository.THEME_DARK)
        val original = RuntimeOptions(queueSeconds = 42)
        repository.setRuntimeOptions(original)
        val arrivals = AtomicInteger()
        val bothSubmitted = CompletableDeferred<Unit>()
        // Hold both submissions before acquiring the store lock. An implementation
        // which reads outside edit now deterministically has two stale snapshots.
        store.beforeUpdate = {
            if (arrivals.incrementAndGet() == 2) bothSubmitted.complete(Unit)
            bothSubmitted.await()
        }
        withTimeout(3000) {
            coroutineScope {
                launch { repository.updateRuntimeGroup(RuntimeOptionGroup.CPU, mapOf("asr_threads" to "8")) }
                launch { repository.updateRuntimeGroup(RuntimeOptionGroup.DIARIZATION, mapOf("speaker_threshold" to "0.7")) }
            }
        }
        assertEquals(original.copy(asrThreads = 8, speakerThreshold = 0.7f), repository.runtimeOptions.first())
        assertEquals(SettingsRepository.THEME_DARK, repository.themeMode.first())
        assertEquals("Each group must use one edit transaction", 2, arrivals.get())
    }

    @Test fun invalidGroupDraftLeavesEveryPreferenceUntouched() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        repository.setRuntimeOptions(RuntimeOptions(asrThreads = 8, nativeFifoFrames = 32, nativeUpdateFrames = 32))
        val before = store.data.value.asMap()
        try {
            repository.updateRuntimeGroup(RuntimeOptionGroup.EXPERIMENTAL, mapOf("native_fifo_frames" to "16"))
            fail("FIFO cannot become smaller than the current update size")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, store.data.value.asMap())
        repository.updateRuntimeGroup(RuntimeOptionGroup.EXPERIMENTAL,
            mapOf("native_fifo_frames" to "16", "native_update_frames" to "16"))
        assertEquals(RuntimeOptions(asrThreads = 8, nativeFifoFrames = 16, nativeUpdateFrames = 16), repository.runtimeOptions.first())
    }

    @Test fun groupDraftCannotOverwriteAnotherGroupsKeys() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        val before = store.data.value.asMap()
        try {
            repository.updateRuntimeGroup(RuntimeOptionGroup.CPU, mapOf("queue_seconds" to "42"))
            fail("A group edit must reject keys from other groups")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, store.data.value.asMap())
    }

    @Test fun streamStatisticsDefaultOffAndRemainIndependentOfDiagnostics() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        // Existing installations can already have logging enabled when this UI option arrives.
        repository.setRuntimeOptions(RuntimeOptions(diagnostics = true))
        assertFalse(repository.showTranscriptionStreamStatistics.first())
        repository.setShowTranscriptionStreamStatistics(true)
        assertTrue(SettingsRepository(store).showTranscriptionStreamStatistics.first())
        repository.resetRuntimeOptions()
        assertTrue(repository.showTranscriptionStreamStatistics.first())
        assertFalse(repository.runtimeOptions.first().diagnostics)
        repository.setRuntimeOptions(RuntimeOptions(diagnostics = true))
        repository.setShowTranscriptionStreamStatistics(false)
        assertFalse(SettingsRepository(store).showTranscriptionStreamStatistics.first())
        assertTrue(repository.runtimeOptions.first().diagnostics)
    }
}
