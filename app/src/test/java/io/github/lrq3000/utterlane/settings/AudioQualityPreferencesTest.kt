package io.github.lrq3000.utterlane.settings

import io.github.lrq3000.utterlane.archive.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioQualityPreferencesTest {
    @Test fun snapshotDoesNotMutateAndTuplePersistsAtomically() = runBlocking {
        val memory = MemoryStore()
        val settings = SettingsRepository(memory)
        assertEquals(AudioQuality(), settings.archival.first().quality)
        val native = settings.archival.first()
        val selected = AudioQuality(true, SavedAudioFormat.M4A, 44100, 2, 192000)
        settings.setAudioQuality(selected)
        assertEquals(selected, SettingsRepository(memory).archival.first().quality)
        assertEquals(AudioQuality(), native.quality)
        try { settings.setAudioQuality(AudioQuality(true, bitRate = 192000)); fail("Invalid AAC tuple was persisted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(selected, settings.archival.first().quality)
    }
}
