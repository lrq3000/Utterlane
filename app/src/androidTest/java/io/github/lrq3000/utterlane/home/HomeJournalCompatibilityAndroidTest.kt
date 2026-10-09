package io.github.lrq3000.utterlane.home

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import io.github.lrq3000.utterlane.transcribe.DialogInput
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complements the parent's epoch-zero regression with old-journal migration. */
@RunWith(AndroidJUnit4::class)
class HomeJournalCompatibilityAndroidTest {
    @Test fun legacyZeroIsUnknownAndAnExplicitAbsenceWinsOverOldBytes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "home-journal-compat-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("audio", "fixture").putLong("created", 0).commit()
            assertNull(HomeJournal(context).restore()!!.metadata!!.created)
            prefs.edit().putLong("created", 123).commit()
            assertEquals(123L, HomeJournal(context).restore()!!.metadata!!.created)
            prefs.edit().putBoolean("has_created", false).commit()
            assertNull(HomeJournal(context).restore()!!.metadata!!.created)

            val journal = HomeJournal(context)
            val beforeEpoch = DialogInput(audioId = "fixture", transcriptOrigin = false,
                metadata = TranscriptMetadata(created = -123, durationMs = 456, speakerLabels = true))
            journal.write(beforeEpoch)
            assertEquals(beforeEpoch, HomeJournal(context).restore())
            journal.write(beforeEpoch.copy(metadata = TranscriptMetadata()))
            assertNull(HomeJournal(context).restore()!!.metadata!!.created)
        } finally { base.deleteSharedPreferences(name) }
    }
}
