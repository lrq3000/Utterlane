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

class RuntimeOptionsPersistenceTest {
    // DataStore 1.0 uses File.renameTo to replace files, which is not portable to
    // Windows JVM tests. Exercise repository transactions against an in-memory store;
    // Android still owns the real on-disk DataStore and its atomic file replacement.
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    @Test fun snapshotWritesAreAtomicAndResetKeepsOtherPreferences() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
            repository.setThemeMode(SettingsRepository.THEME_DARK)
            assertEquals(RuntimeOptions(), repository.runtimeOptions.first())
            val options = RuntimeOptions(nativeFifoFrames = 32, nativeUpdateFrames = 32, diagnostics = true)
            repository.setRuntimeOptions(options)
            assertEquals(options, repository.runtimeOptions.first())
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
}
