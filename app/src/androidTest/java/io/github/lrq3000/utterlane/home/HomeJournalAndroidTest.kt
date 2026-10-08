package io.github.lrq3000.utterlane.home

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import io.github.lrq3000.utterlane.transcribe.DialogInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeJournalAndroidTest {
    @Test fun processReconstructionPreservesIndependentMetadataButNeverRestartsCapture() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "home-journal-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val original = DialogInput(audioId = "audio", transcriptId = "text", transcriptPath = "/private/working.txt",
                transcriptOrigin = false, modelName = "Model", modelId = "model-id",
                metadata = TranscriptMetadata(123456L, 7800L, true))
            HomeJournal(context).write(original)
            val restored = HomeJournal(context).restore()!!
            assertEquals(original, restored)
            assertFalse(restored.automatic)
            assertNull(restored.uri)
            // Before async model hydration, saveInstanceState exposes null text
            // identifiers. Persist that exact checkpoint and recreate the journal
            // to verify the earlier descriptor still survives process loss.
            val descriptor = HomeResultDescriptor(restored)
            HomeJournal(context).write(descriptor.update(restored.copy(transcriptId = null, transcriptPath = null), importing = true))
            assertEquals(original, HomeJournal(context).restore())
            HomeJournal(context).clear()
            assertNull(HomeJournal(context).restore())
        } finally { base.deleteSharedPreferences(name) }
    }
}
