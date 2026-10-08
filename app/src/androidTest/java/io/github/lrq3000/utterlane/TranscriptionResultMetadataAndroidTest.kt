package io.github.lrq3000.utterlane

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.*
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test fun cacheOnlyResultMetadataIsWrittenToSavedState() = runBlocking {
        val source = workingText()
        val savedState = Bundle()
        var savedId: String? = null
        try {
            DialogOwner(DialogInput(audioId = "missing-fixture-audio", transcriptPath = source.absolutePath,
                modelName = "Fixture model", metadata = metadata)).use { owner ->
                owner.ready()
                assertNull(owner.model.state.value.audio)
                instrumentation.runOnMainSync { owner.model.saveInstanceState(savedState) }
                assertEquals(source.canonicalPath, savedState.getString("working_text"))
                val savedMetadata = savedState.getBundle("result_metadata")
                assertNotNull("Cache-only results need metadata even when source audio is gone", savedMetadata)
                assertEquals(metadata.created, savedMetadata!!.getLong("created"))
                assertEquals(metadata.durationMs, savedMetadata.getLong("durationMs"))
                assertEquals(metadata.speakerLabels, savedMetadata.getBoolean("speakerLabels"))
            }
            // Drop the old ViewModel owner and cross the Bundle parcel boundary;
            // no source audio or saved text entry can supply missing metadata.
            val restoredState = parcel(savedState)
            DialogOwner(DialogInput(audioId = restoredState.getString("owned_audio"),
                transcriptPath = restoredState.getString("working_text"), modelName = restoredState.getString("result_model").orEmpty(),
                metadata = TranscriptMetadata.fromBundle(restoredState))).use { owner ->
                owner.ready()
                assertEquals(metadata, owner.model.metadata)
                owner.pin(true)
                savedId = owner.model.state.value.transcriptId
                val saved = app.transcriptHistory.get(checkNotNull(savedId))
                assertEquals(metadata, TranscriptMetadata(saved))
                assertEquals("Fixture model", saved.model)
                assertEquals("missing-fixture-audio", saved.audioId)
                assertEquals(source.readText(), saved.file.readText())
            }
        } finally { savedId?.let(app.transcriptHistory::delete); source.delete() }
    }

    @Test fun metadataCodecPreservesUnknownAndEpochTimestamps() {
        val state = Bundle()
        for (snapshot in listOf(metadata, TranscriptMetadata(), TranscriptMetadata(created = 0))) {
            snapshot.writeToBundle(state)
            assertEquals(snapshot, TranscriptMetadata.fromBundle(parcel(state)))
        }
        assertNull(TranscriptMetadata.fromBundle(null))
        assertNull(TranscriptMetadata.fromBundle(Bundle()))
    }

    @Test fun legacyCacheOnlyStateStillRestoresWithoutInventingMetadata() = runBlocking {
        val source = workingText()
        val oldState = Bundle().apply { putString("working_text", source.absolutePath) }
        try {
            DialogOwner(DialogInput(transcriptPath = oldState.getString("working_text"),
                metadata = TranscriptMetadata.fromBundle(oldState))).use { owner ->
                owner.ready()
                assertEquals(TranscriptMetadata(), owner.model.metadata)
                assertEquals(source.readText(), owner.model.state.value.preview)
            }
        } finally { source.delete() }
    }

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

    @Test fun explicitDeleteRemovesReplacementPublishedByPendingKeep() = runBlocking {
        dismissDuringPendingKeep(delete = true)
    }

    @Test fun ordinaryDismissPreservesReplacementPublishedByPendingKeep() = runBlocking {
        dismissDuringPendingKeep(delete = false)
    }

    private suspend fun dismissDuringPendingKeep(delete: Boolean) {
        val source = workingText()
        val root = File.createTempFile("pending-keep-", "", app.cacheDir).apply { delete(); mkdir() }
        val saveEntered = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val pauseNextSave = AtomicBoolean(false)
        val history = TranscriptHistory(root) {
            // The repository's normal clock dependency provides a boundary
            // after mkdir and before publishing a real saved entry. No production
            // hooks, fake save results or timing sleeps control this race.
            if (pauseNextSave.compareAndSet(true, false)) {
                saveEntered.countDown()
                check(releaseSave.await(10, TimeUnit.SECONDS)) { "Replacement save was not released" }
            }
            System.currentTimeMillis()
        }
        val original = metadata.save(history, source, "Fixture model", pinned = true)
        val originalLease = history.acquire(original.id)
        val originalHistory = app.transcriptHistory
        // Scope the singleton dependency swap to this fixture. Background cleanup
        // cannot touch other entries through this isolated repository, and all
        // production model/Keep/Delete operations remain the real implementations.
        val field = UtterlaneApp::class.java.getDeclaredField("transcriptHistory").apply { isAccessible = true }
        val dismissed = CompletableDeferred<Unit>()
        var owner: DialogOwner? = null
        try {
            instrumentation.runOnMainSync { field.set(app, history) }
            val dialog = DialogOwner(DialogInput(transcriptId = original.id, transcriptPath = source.absolutePath))
            owner = dialog
            dialog.ready()
            // The dialog still owns working text and the old ID. A prior cleanup
            // makes the next Keep exercise the missing-entry replacement path.
            history.delete(original.id)
            pauseNextSave.set(true)
            instrumentation.runOnMainSync { dialog.model.setTranscriptPinned(true) }
            assertTrue("Keep never reached replacement publication", saveEntered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { dialog.model.dismiss(delete) { dismissed.complete(Unit) } }
            if (delete) {
                // Save holds the repository monitor. Observe dismissal blocked at
                // its early delete call, proving it already captured the OLD ID
                // before allowing Keep to publish its replacement. This condition
                // removes dependence on thread scheduling or arbitrary delays.
                withTimeout(5000) {
                    while (!Thread.getAllStackTraces().any { (thread, stack) ->
                        thread.state == Thread.State.BLOCKED &&
                            stack.any { it.className == TranscriptHistory::class.java.name && it.methodName == "delete" } &&
                            stack.any { it.className.startsWith(TranscriptionDialogModel::class.java.name + "\$dismiss") }
                    }) delay(10)
                }
                val marker = java.util.Properties().apply {
                    File(original.directory, "transcript.properties").inputStream().use { load(it) }
                }
                assertEquals("true", marker.getProperty("discarded"))
            }
            releaseSave.countDown()
            withTimeout(5000) { dismissed.await() }
            val replacementId = checkNotNull(dialog.model.state.value.transcriptId)
            assertNotEquals("The pending operation must really create a new copy", original.id, replacementId)
            assertThrows(IllegalStateException::class.java) { history.get(original.id) }
            originalLease.close()
            assertFalse(original.directory.exists())
            if (delete) {
                assertThrows("Explicit Delete must win over the pending Keep", IllegalStateException::class.java) { history.get(replacementId) }
                assertFalse(File(root, replacementId).exists())
                assertTrue(history.list().isEmpty())
            } else {
                assertTrue(history.get(replacementId).retention.pinned)
                assertEquals(listOf(replacementId), history.list().map { it.id })
            }
        } finally {
            releaseSave.countDown()
            // Join the actual operations before restoring the shared dependency,
            // including when a race-boundary assertion fails.
            try {
                owner?.let { dialog ->
                    try {
                        withTimeout(5000) {
                            dialog.model.state.first { !it.saving }
                            if (dialog.model.state.value.closing) dismissed.await()
                        }
                    } finally { dialog.close() }
                }
            } finally {
                instrumentation.runOnMainSync { field.set(app, originalHistory) }
                originalLease.close()
                root.deleteRecursively()
                source.delete()
            }
        }
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

    private fun parcel(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            checkNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
    }

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
