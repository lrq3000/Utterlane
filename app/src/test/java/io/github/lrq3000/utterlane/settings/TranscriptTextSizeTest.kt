package io.github.lrq3000.utterlane.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTextSizeTest {
    @Test fun theReaderKeepsItsSizeAcrossSessionsWithoutChangingTheTheme() = runBlocking {
        val store = MemoryStore()
        val settings = SettingsRepository(store)
        val theme = settings.themeMode.first()
        assertEquals(16, settings.transcriptTextSize.first())
        settings.setTranscriptTextSize(24)
        val next = SettingsRepository(store)
        assertEquals(24, next.transcriptTextSize.first())
        assertEquals(theme, next.themeMode.first())
        store.edit { it[intPreferencesKey("transcript_text_size_sp")] = -1 }
        assertEquals(16, next.transcriptTextSize.first())
    }
}
