package io.github.lrq3000.utterlane.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportRetentionPreferencesTest {
    @Test fun rememberedChoicesSurviveRepositoryRecreationAndResetIndependentlyOfHistory() = runBlocking {
        val store = MemoryStore()
        val settings = SettingsRepository(store)
        assertNull(settings.importRetentionPreferences.first().durationChoice)
        settings.setAudioHistoryEnabled(false)
        settings.setAudioHistoryRetention(HistoryRetention.WEEK)
        settings.rememberImportRetention(ImportRetentionCategory.DURATION, ImportRetentionChoice.KEEP)
        settings.rememberImportRetention(ImportRetentionCategory.SIZE, ImportRetentionChoice.DISCARD)
        val recreated = SettingsRepository(store)
        assertEquals(ImportRetentionPreferences(false, ImportRetentionChoice.KEEP, ImportRetentionChoice.DISCARD),
            recreated.importRetentionPreferences.first())
        recreated.resetImportRetentionChoices()
        assertEquals(ImportRetentionPreferences(false), settings.importRetentionPreferences.first())
        assertEquals(HistoryRetention.WEEK, settings.audioHistoryRetention.first())
    }

    @Test fun unknownStoredChoiceAsksAgainAndLegacyHistoryOffStillWins() = runBlocking {
        val store = MemoryStore()
        store.edit {
            it[stringPreferencesKey("history_retention")] = "none"
            it[stringPreferencesKey("import_retention_duration")] = "unrecognized"
            it[stringPreferencesKey("import_retention_size")] = "KEEP"
        }
        val preferences = SettingsRepository(store).importRetentionPreferences.first()
        assertFalse(preferences.audioHistoryEnabled)
        assertNull(preferences.durationChoice)
        assertEquals(ImportRetentionChoice.KEEP, preferences.sizeChoice)
        assertEquals(ImportRetentionDecision.Use(ImportRetentionChoice.DISCARD),
            ImportRetentionPolicy.decide(600001, Long.MAX_VALUE, preferences))
    }
}
