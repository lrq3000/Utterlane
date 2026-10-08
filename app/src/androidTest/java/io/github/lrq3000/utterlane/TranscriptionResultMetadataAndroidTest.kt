package io.github.lrq3000.utterlane

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.*
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Own only these fixtures; the shared QA app's preferences and other results stay intact. */
@RunWith(AndroidJUnit4::class)
class TranscriptionResultMetadataAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp

    @Test fun successfulTemporaryResultDoesNotReportInterruption() = runBlocking {
        val recording = app.recordingHistory.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recording.append(ShortArray(1600)); recording.finish(false)
        try {
            DialogOwner(DialogInput(audioId = recording.entry.id, automatic = false)).use { owner ->
                owner.ready()
                assertNull(owner.model.state.value.message)
                val audio = app.recordingHistory.get(recording.entry.id)
                assertEquals("saved", audio.status)
                assertTrue(audio.temporary)
                assertTrue("Suppressing a false interruption must not discard recovery protection", audio.needsRecovery)
            }
        } finally { app.recordingHistory.delete(recording.entry.id) }
    }

    @Test fun actualFailedTemporaryResultStillExplainsRecovery() = runBlocking {
        val recording = app.recordingHistory.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recording.append(ShortArray(1600)); recording.finish(true)
        try {
            DialogOwner(DialogInput(audioId = recording.entry.id)).use { owner ->
                owner.ready()
                assertEquals(app.getString(R.string.dialog_recovery_info), owner.model.state.value.message)
                assertTrue(app.recordingHistory.get(recording.entry.id).needsRecovery)
            }
        } finally { app.recordingHistory.delete(recording.entry.id) }
    }

    @Test fun saveAfterHistoryDeletionCreatesANewPinnedCopy() = runBlocking {
        saveAfterDeletion(leased = false)
    }

    @Test fun saveAfterLeasedHistoryDeletionDoesNotResurrectTheOldId() = runBlocking {
        saveAfterDeletion(leased = true)
    }

    @Test fun unpinAfterHistoryDeletionDoesNotCreateAReplacement() = runBlocking {
        val source = workingText()
        val saved = metadata.save(app.transcriptHistory, source, "Fixture model", pinned = true)
        try {
            DialogOwner(DialogInput(transcriptId = saved.id, transcriptPath = source.absolutePath)).use { owner ->
                owner.ready()
                app.transcriptHistory.delete(saved.id)
                owner.pin(false)
                assertNull(owner.model.state.value.transcriptId)
                assertFalse(owner.model.state.value.transcriptPinned)
                assertTrue("The working text must remain usable", source.isFile)
            }
        } finally { app.transcriptHistory.delete(saved.id); source.delete() }
    }

    private suspend fun saveAfterDeletion(leased: Boolean) {
        val source = workingText()
        // Use the same attempt as the working text: a new explicit Save cannot
        // reuse its deleted automatic-save ID, even while export holds a lease.
        val saved = metadata.save(app.transcriptHistory, source, "Fixture model", "missing-fixture-audio", pinned = true)
        var lease: Closeable? = null
        var replacementId: String? = null
        try {
            DialogOwner(DialogInput(transcriptId = saved.id, transcriptPath = source.absolutePath)).use { owner ->
                owner.ready()
                if (leased) lease = app.transcriptHistory.acquire(saved.id)
                app.transcriptHistory.delete(saved.id)
                owner.pin(true)
                replacementId = owner.model.state.value.transcriptId
                assertNotNull("Explicit Save must preserve the still-owned working text", replacementId)
                assertNotEquals("Deleted IDs must not be resurrected", saved.id, replacementId)
                val replacement = app.transcriptHistory.get(checkNotNull(replacementId))
                assertTrue(replacement.retention.pinned)
                assertEquals(metadata.created, replacement.created)
                assertEquals(metadata.durationMs, replacement.durationMs)
                assertEquals(metadata.speakerLabels, replacement.speakerLabels)
                assertEquals(saved.audioId, replacement.audioId)
                assertEquals(source.readText(), replacement.file.readText())
                assertThrows(IllegalStateException::class.java) { app.transcriptHistory.get(saved.id) }
                if (leased) assertTrue("A lease still owns the discarded original", saved.file.isFile)
                owner.pin(true)
                assertEquals("Repeated Keep should pin the replacement, not duplicate it", replacementId, owner.model.state.value.transcriptId)
            }
        } finally {
            lease?.close()
            replacementId?.takeUnless { it == saved.id }?.let(app.transcriptHistory::delete)
            app.transcriptHistory.delete(saved.id)
            source.delete()
        }
    }

    private val metadata = TranscriptMetadata(created = 123456L, durationMs = 3456L, speakerLabels = true)

    private fun workingText(): File = File.createTempFile("result-metadata-", ".txt",
        File(app.cacheDir, "transcripts").apply { mkdirs() }).apply { writeText("Speaker 1: fixture words") }

    private inner class DialogOwner(input: DialogInput) : Closeable {
        private val owners = ViewModelStore()
        lateinit var model: TranscriptionDialogModel
            private set

        init {
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, input)
                owners.put("result", model)
            }
        }

        suspend fun ready() {
            withTimeout(5000) { model.state.first { !it.importing } }
            // importing=false is published before the initialization message;
            // inspect only after that Main-dispatch operation has returned.
            instrumentation.runOnMainSync { }
        }

        suspend fun pin(value: Boolean) {
            instrumentation.runOnMainSync { model.setTranscriptPinned(value) }
            withTimeout(5000) { model.state.first { !it.saving } }
            instrumentation.runOnMainSync { }
        }

        override fun close() { instrumentation.runOnMainSync { owners.clear() } }
    }
}
