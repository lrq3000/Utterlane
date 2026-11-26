package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import io.github.lrq3000.utterlane.history.ImportRetentionCategory
import io.github.lrq3000.utterlane.history.ImportRetentionChoice
import io.github.lrq3000.utterlane.history.AudioImportMetadata
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.history.HistoryRetention
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ImportBatchRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun processDeathAfterCompletePrivateCopyAdoptsItWithoutReadableProviderOrSecondCopy() {
        val root = temp.newFolder()
        val audioRoot = temp.newFolder()
        val id = UUID.randomUUID().toString()
        val bytes = byteArrayOf(3, 1, 4)
        ImportBatchStore(root).use { store ->
            store.create(id, listOf("temporary://one", "temporary://two"), false)
            val item = store.get(id)!!
            val history = RecordingHistory(audioRoot).apply { awaitingQueueReview = store::protectsAudio }
            copyImportedAudio(history, bytes.inputStream(), "wav", AudioImportMetadata(), ImportRetentionChoice.KEEP, false, item.jobId).lease.close()
        }
        ImportBatchStore(root).use { recovered ->
            val item = recovered.get(id)!!
            val history = RecordingHistory(audioRoot).apply { awaitingQueueReview = recovered::protectsAudio }
            history.prune(HistoryRetention.NONE)
            val staged = checkNotNull(history.stagedImport(item.jobId))
            staged.lease.use {
                assertArrayEquals(bytes, staged.entry.part(0).readBytes())
                assertEquals(item.jobId, history.list().single().id)
                QueueJournal(root).add(staged.entry.id, "one", false, id = item.jobId)
                recovered.advance(item, accepted = true)
            }
            assertEquals("temporary://two", recovered.get(id)!!.uri)
        }
    }

    @Test fun lastBatchReleasesOnlyItsOwnReadGrant() {
        val root = temp.newFolder()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val released = mutableListOf<String>()
        ImportBatchStore(root).use { store ->
            store.create(a, listOf("existing", "new"), false)
            store.create(b, listOf("new"), false)
            store.takeGrant("existing", { true }) { fail("Pre-existing access cannot be claimed") }
            store.takeGrant("new", { false }) { }
            store.forget(a)
            store.releaseUnused { released.add(it) }
            assertTrue(released.isEmpty())
        }
        ImportBatchStore(root).use { store ->
            store.forget(b)
            store.releaseUnused { released.add(it) }
        }
        assertEquals(listOf("new"), released)
    }

    @Test fun validBadValidPublishesBothReadableSiblingsBeforeAskingToReselect() = kotlinx.coroutines.runBlocking<Unit> {
        val root = temp.newFolder()
        val id = UUID.randomUUID().toString()
        val queue = QueueJournal(root)
        ImportBatchStore(root).use { store ->
            store.create(id, listOf("first", "revoked", "last"), false)
            store.runPass(id, null, { false }) { current ->
                if (current.uri == "revoked") throw ImportSourceFailure(SecurityException("provider grant revoked"))
                queue.add(current.jobId, current.uri!!, false, id = current.jobId)
                store.advance(current, accepted = true)
                true
            }
            assertEquals(listOf("first", "last"), queue.records().map { it.label })
        }
        ImportBatchStore(root).use { restored ->
            val missing = restored.get(id)!!
            assertEquals(2, missing.accepted)
            assertEquals("revoked", missing.uri)
            assertTrue(missing.deferred)
            assertFalse(restored.replace(missing, "last"))
            assertTrue(restored.replace(missing, "granted-again"))
            restored.advance(missing, accepted = true)
            assertTrue(restored.get(id)!!.done)
            assertEquals(3, restored.get(id)!!.accepted)
        }
    }
    @Test fun recreatedImportReconcilesPublishedPrefixAndNeverUsesItAsReplacementInput() {
        val root = temp.newFolder()
        val id = UUID.randomUUID().toString()
        val queue = QueueJournal(root)
        val first = ImportBatchStore(root).use { store ->
            store.create(id, listOf("content://share/a", "content://share/b", "content://share/c"), true)
            store.answer(id, ImportRetentionCategory.SIZE, ImportRetentionChoice.DISCARD)
            val current = store.get(id)!!
            queue.add(current.jobId, "first", true, id = current.jobId)
            current.jobId // Process dies after publishing the job, before its cursor update.
        }
        ImportBatchStore(root).use { restored ->
            restored.reconcile(queue)
            val second = restored.get(id)!!
            assertEquals("content://share/b", second.uri)
            assertEquals(1, second.accepted)
            assertEquals(first, second.firstJob)
            assertTrue(second.speakers)
            assertEquals(ImportRetentionChoice.DISCARD, second.choices[ImportRetentionCategory.SIZE])
            queue.update(queue.find(first)!!.copy(stage = QueueStage.READY))
            assertTrue(queue.acknowledge(first)) // The old row need not exist for deduplication.
            assertFalse(restored.replace(second, "content://share/a"))
            assertTrue(restored.replace(second, "content://documents/b"))
            assertEquals(second.jobId, restored.get(id)!!.jobId)
            restored.advance(second, accepted = true)
            assertEquals("content://share/c", restored.get(id)!!.uri)
            restored.advance(second, accepted = true) // Stale delivery cannot skip the third member.
            assertEquals(2, restored.get(id)!!.accepted)
        }
        ImportBatchStore(root).use { assertEquals("content://share/c", it.get(id)!!.uri) }
    }
}
