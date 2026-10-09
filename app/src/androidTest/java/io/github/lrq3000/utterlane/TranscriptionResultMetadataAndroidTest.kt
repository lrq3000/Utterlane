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

    @Test fun interruptedLabelCheckpointSurvivesAudioHydrationAndExplicitKeep() = runBlocking {
        val recording = app.recordingHistory.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recording.append(ShortArray(16000)); recording.finish(true)
        val source = workingText()
        var savedId: String? = null
        try {
            // The process stopped before the microphone finalizer could mark its
            // audio. The committed text checkpoint is independently authoritative.
            assertFalse(app.recordingHistory.get(recording.entry.id).speakerLabels)
            DialogOwner(DialogInput(audioId = recording.entry.id, transcriptPath = source.absolutePath,
                metadata = TranscriptMetadata(speakerLabels = true))).use { owner ->
                owner.ready()
                assertTrue("Audio hydration must not erase committed text labels", owner.model.metadata.speakerLabels)
                assertEquals(recording.entry.started, owner.model.metadata.created)
                assertEquals(1000L, owner.model.metadata.durationMs)
                owner.pin(true)
                savedId = owner.model.state.value.transcriptId
                assertTrue(app.transcriptHistory.get(checkNotNull(savedId)).speakerLabels)
            }
        } finally {
            savedId?.let(app.transcriptHistory::delete)
            app.recordingHistory.delete(recording.entry.id)
            source.delete()
        }
    }

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

    @Test fun explicitKeepInAnotherOwnerCannotUndoConfirmedWorkingSourceDeletion() = runBlocking {
        val source = workingText()
        val saved = metadata.save(app.transcriptHistory, source, "Fixture model", pinned = true)
        try {
            DialogOwner(DialogInput(transcriptId = saved.id, transcriptPath = source.absolutePath)).use { producer ->
                producer.ready()
                val working = checkNotNull(producer.model.state.value.store)
                android.util.Log.i("TranscriptMetadataQA", "Input=${source.absolutePath}, working=${working.file.absolutePath}, canonical=${working.file.canonicalPath}")
                // This assertion is about an external reader, not incidental
                // ViewModel ownership. Lease the actual restored/copied store.
                working.acquire().use {
                    DialogOwner(DialogInput(transcriptId = saved.id)).use { deletion ->
                        deletion.ready()
                        instrumentation.runOnMainSync { deletion.model.requestDeletion() }
                        withTimeout(5000) { deletion.model.state.first { !it.checkingDeletion && it.deletion != null } }
                        instrumentation.runOnMainSync { deletion.model.confirmDeletion() }
                        withTimeout(5000) { deletion.model.state.first { !it.deleting && it.finished } }
                        assertTrue("The external reader still leases ${working.file}", working.file.isFile)
                        producer.pin(true)
                        assertNull(app.transcriptHistory.find(saved.id))
                        assertNull(app.transcriptHistory.find(producer.model.state.value.transcriptId))
                        assertTrue(io.github.lrq3000.utterlane.asr.TranscriptSource.read(working.file).discarded)
                        assertNotNull(producer.model.state.value.message)
                    }
                }
            }
        } finally { app.transcriptHistory.delete(saved.id); source.delete() }
    }

    @Test fun confirmedDeleteReconfirmsReplacementPublishedByPendingKeep() = runBlocking {
        dismissDuringPendingKeep(delete = true)
    }

    @Test fun twoOwnersReconfirmKeepPausedBetweenSaveAndSourceAttachment() = runBlocking {
        val source = workingText()
        val original = metadata.save(app.transcriptHistory, source, "Fixture model", "two-owner-audio", pinned = true)
        val sibling = app.transcriptHistory.save(source, "Other model", original.audioId, attempt = "sibling-${source.name}")
        var replacement: String? = null
        try {
            DialogOwner(DialogInput(transcriptId = original.id, transcriptPath = source.absolutePath)).use { keeper ->
                keeper.ready()
                DialogOwner(DialogInput(transcriptId = original.id, transcriptPath = source.absolutePath)).use { deleter ->
                    deleter.ready()
                    app.transcriptHistory.delete(original.id)
                    instrumentation.runOnMainSync { deleter.model.requestDeletion() }
                    withTimeout(5000) { deleter.model.state.first { !it.checkingDeletion && it.deletion != null } }
                    assertEquals(setOf(original.id), deleter.model.state.value.deletion!!.plan.transcriptIds)
                    // Hold only this owner's store monitor. The real Keep worker
                    // saves N, then blocks at attachSource(N); the other model is
                    // free to attempt confirmation using its independent store.
                    synchronized(checkNotNull(keeper.model.state.value.store)) {
                        instrumentation.runOnMainSync { keeper.model.setPinned(DialogPinTarget.TRANSCRIPT, true) }
                        awaitBlockedWorker("attachSource", "saveWorking")
                        instrumentation.runOnMainSync { deleter.model.confirmDeletion() }
                        awaitBlockedWorker("withPublicationLock", "confirmDeletion")
                        assertTrue(deleter.model.state.value.deleting)
                        assertFalse(io.github.lrq3000.utterlane.asr.TranscriptSource.read(source).discarded)
                    }
                    withTimeout(5000) { keeper.model.state.first { !it.saving } }
                    withTimeout(5000) { deleter.model.state.first { !it.deleting && it.deletion != null } }
                    replacement = keeper.model.state.value.transcriptId
                    assertNotNull(replacement)
                    assertNotEquals(original.id, replacement)
                    assertEquals(setOf(replacement), deleter.model.state.value.deletion!!.plan.transcriptIds)
                    assertTrue(app.transcriptHistory.get(checkNotNull(replacement)).retention.pinned)
                    instrumentation.runOnMainSync { deleter.model.confirmDeletion() }
                    withTimeout(5000) { deleter.model.state.first { !it.deleting && it.finished } }
                    assertNull(app.transcriptHistory.find(replacement))
                    assertEquals(listOf(sibling.id), app.transcriptHistory.forAudio(original.audioId!!).map { it.id })
                }
            }
        } finally {
            replacement?.let(app.transcriptHistory::delete)
            app.transcriptHistory.delete(original.id); app.transcriptHistory.delete(sibling.id)
            source.delete()
        }
    }

    private fun awaitBlockedWorker(method: String, caller: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().any { (thread, stack) ->
                thread.state == Thread.State.BLOCKED && stack.any { it.methodName == method } &&
                    stack.any { it.methodName == caller }
            }) return
            Thread.yield()
        }
        fail("No worker blocked in $method from $caller")
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
        val original = metadata.save(history, source, "Fixture model", audioId = "pending-keep-audio", pinned = true)
        val sibling = history.save(source, "Other model", audioId = original.audioId, attempt = "sibling-${source.name}")
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
            if (delete) {
                instrumentation.runOnMainSync { dialog.model.requestDeletion() }
                withTimeout(5000) { dialog.model.state.first { !it.checkingDeletion && it.deletion != null } }
                assertEquals(setOf(original.id), dialog.model.state.value.deletion!!.plan.transcriptIds)
                assertFalse(dialog.model.state.value.deletion!!.plan.allLinked)
            }
            // The dialog still owns working text and the old ID. A prior cleanup
            // makes the next Keep exercise the missing-entry replacement path.
            history.delete(original.id)
            pauseNextSave.set(true)
            instrumentation.runOnMainSync { dialog.model.setPinned(DialogPinTarget.TRANSCRIPT, true) }
            assertTrue("Keep never reached replacement publication", saveEntered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                if (delete) dialog.model.confirmDeletion()
                else dialog.model.dismiss { dismissed.complete(Unit) }
            }
            // The confirmation now waits for Keep, then compares exact identities.
            // A new ID is a changed scope, requiring a fresh confirmation rather
            // than silently extending the original deletion to the replacement.
            if (delete) assertTrue(dialog.model.state.value.deleting)
            releaseSave.countDown()
            withTimeout(5000) {
                if (delete) dialog.model.state.first { !it.saving && !it.deleting && it.deletion != null }
                else dismissed.await()
            }
            val replacementId = checkNotNull(dialog.model.state.value.transcriptId)
            assertNotEquals("The pending operation must really create a new copy", original.id, replacementId)
            assertThrows(IllegalStateException::class.java) { history.get(original.id) }
            originalLease.close()
            assertFalse(original.directory.exists())
            if (delete) {
                assertEquals(setOf(replacementId), dialog.model.state.value.deletion!!.plan.transcriptIds)
                assertNotNull(history.find(replacementId))
                instrumentation.runOnMainSync { dialog.model.confirmDeletion() }
                withTimeout(5000) { dialog.model.state.first { !it.deleting && it.finished } }
                assertThrows("Explicit Delete must win over the pending Keep", IllegalStateException::class.java) { history.get(replacementId) }
                assertFalse(File(root, replacementId).exists())
                assertEquals(listOf(sibling.id), history.list().map { it.id })
            } else {
                assertTrue(history.get(replacementId).retention.pinned)
                assertEquals(setOf(replacementId, sibling.id), history.list().map { it.id }.toSet())
            }
        } finally {
            releaseSave.countDown()
            // Join the actual operations before restoring the shared dependency,
            // including when a race-boundary assertion fails.
            try {
                owner?.let { dialog ->
                    try {
                        withTimeout(5000) {
                            dialog.model.state.first { !it.saving && !it.deleting }
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
            instrumentation.runOnMainSync { model.setPinned(DialogPinTarget.TRANSCRIPT, value) }
            withTimeout(5000) { model.state.first { !it.saving } }
            instrumentation.runOnMainSync { }
        }

        override fun close() { instrumentation.runOnMainSync { owners.clear() } }
    }
}
