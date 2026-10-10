package io.github.lrq3000.utterlane.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FloatingButtonSizeTest {
    @Test fun existingPresetsAndNewInstallationsRetainTheirDiameters() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        assertEquals(56, repository.floatingButtonSizeDp.first())
        assertFalse(repository.hasSavedSettings.first())
        for ((key, dp) in listOf("small" to 44, "medium" to 56, "large" to 72)) {
            store.edit { it[stringPreferencesKey("floating_button_size")] = key }
            assertEquals(dp, SettingsRepository(store).floatingButtonSizeDp.first())
        }
    }

    @Test fun pinchedSizeIsBoundedPersistedAndOverridesLegacySizeWithoutChangingEnablement() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        repository.setServiceEnabled(true)
        store.edit { it[stringPreferencesKey("floating_button_size")] = "large" }
        for ((requested, expected) in listOf(83 to 83, -100 to 44, 10000 to 144)) {
            repository.setFloatingButtonSizeDp(requested)
            assertEquals(expected, SettingsRepository(store).floatingButtonSizeDp.first())
        }
        assertTrue(repository.serviceEnabled.first())
    }

    @Test fun malformedOrMissingCustomPreferencesHaveSafeFallbacks() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        store.edit { it[stringPreferencesKey("floating_button_size")] = "custom" }
        assertEquals(56, repository.floatingButtonSizeDp.first())
        store.edit { it[stringPreferencesKey("floating_button_size_dp")] = "broken" }
        assertEquals(56, repository.floatingButtonSizeDp.first())
        store.edit { it[intPreferencesKey("floating_button_size_dp")] = Int.MAX_VALUE }
        assertEquals(144, repository.floatingButtonSizeDp.first())
        store.edit { it[stringPreferencesKey("floating_button_size")] = "future" }
        assertEquals(56, repository.floatingButtonSizeDp.first())
    }
}
