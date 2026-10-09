package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Controlled restore/Keep ordering and real lease/sidecar IO boundaries. */
class TranscriptMigrationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun keepAfterRestoreConstructionCannotBeReversedByLegacyMigration() {
        val texts = TranscriptHistory(folder.newFolder())
        val file = folder.newFile().apply { writeText("Recoverable words") }
        val old = TranscriptSource("old-audio", "old-W", "Old model", "old-model")
        old.write(file)
        val constructed = CountDownLatch(1)
        val publishKeep = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val restoring = TranscriptStore(file) // Capture W before the second owner runs.
        val keeper = TranscriptStore(file)
        try {
            val keep = pool.submit<TranscriptEntry> {
                constructed.countDown()
                check(publishKeep.await(5, TimeUnit.SECONDS))
                texts.saveWorking(keeper, "New model", "new-audio", "new-model", TranscriptMetadata())
            }
            assertTrue(constructed.await(5, TimeUnit.SECONDS))
            assertEquals(old, restoring.source)
            publishKeep.countDown()
            val saved = keep.get(5, TimeUnit.SECONDS)
            val expected = TranscriptSource("new-audio", saved.id, "New model", "new-model")
            assertEquals(expected, TranscriptSource.read(file))
            assertSame(restoring, texts.migrateWorking(restoring, old))
            assertEquals("Migration must not republish its constructor's W over Keep's N", expected, TranscriptSource.read(file))
            assertEquals(expected, restoring.source)
            val deletion = LinkedHistory(RecordingHistory(folder.newFolder()), texts)
            val oldQuestion = HistoryDeletionPlan(null, setOf("old-W"), allLinked = false)
            val renewed = deletion.confirmDeletion(oldQuestion, HistoryDeletionTarget.TRANSCRIPTS,
                refresh = { deletion.plan("new-audio", null, true, TranscriptSource.read(file).transcriptId) },
                discardWorking = { fail("The newly published target requires renewed confirmation") })
            assertEquals(setOf(saved.id), renewed!!.transcriptIds)
            assertTrue(texts.get(saved.id).retention.pinned)
        } finally {
            publishKeep.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
            restoring.keepForRecovery(); keeper.keepForRecovery()
        }
    }

    @Test fun migrationWaitsForConfirmationAndRejectsItsDurableDiscardMarker() {
        val texts = TranscriptHistory(folder.newFolder())
        val file = folder.newFile().apply { writeText("Owned words") }
        val legacy = TranscriptSource(transcriptId = "W")
        legacy.write(file)
        val restoring = TranscriptStore(file)
        val reader = restoring.acquire()
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        lateinit var worker: Thread
        try {
            val future = texts.withPublicationLock {
                val migration = pool.submit<TranscriptStore> {
                    worker = Thread.currentThread(); started.countDown()
                    texts.migrateWorking(restoring, legacy)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!migration.isDone && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertFalse("Migration must participate in confirmation's publication transaction", migration.isDone)
                assertEquals(Thread.State.BLOCKED, worker.state)
                TranscriptStore.deleteArtifacts(file)
                migration
            }
            val failure = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is TranscriptDiscardedException)
            assertTrue(TranscriptSource.read(file).discarded)
            // Only the explicit reader survives migration failure; closing it
            // completes already-requested deletion of both artifacts.
            reader.close()
            assertFalse(file.exists())
            assertFalse(TranscriptSource.metadata(file).exists())
        } finally {
            pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
            restoring.keepForRecovery(); reader.close()
        }
    }

    @Test fun failedMigrationReleasesUnexposedLeasesWithoutDiscardingRecoveryBytes() {
        val texts = TranscriptHistory(folder.newFolder())
        val file = folder.newFile().apply { writeText("Keep these exact recovery bytes") }
        val source = TranscriptSource(audioId = "audio")
        source.write(file)
        val restoring = TranscriptStore(file)
        // Block the actual atomic writer, leaving the existing provenance valid.
        File(file.parentFile, file.name + ".source.tmp").mkdir()
        try {
            assertThrows(Exception::class.java) {
                texts.migrateWorking(restoring, TranscriptSource(transcriptId = "legacy-text"))
            }
            assertEquals("Keep these exact recovery bytes", file.readText())
            assertEquals(source, TranscriptSource.read(file))
            CacheArtifacts.deleteWhenReleased(file)
            CacheArtifacts.deleteWhenReleased(TranscriptSource.metadata(file))
            assertFalse("An unexposed owner must not lease the text forever", file.exists())
            assertFalse("An unexposed owner must not lease provenance forever", TranscriptSource.metadata(file).exists())
        } finally { restoring.keepForRecovery() }
    }

    @Test fun successfulMigrationTransfersOneLiveOwnerAndFillsOnlyMissingFields() {
        val texts = TranscriptHistory(folder.newFolder())
        val file = folder.newFile().apply { writeText("Result") }
        TranscriptSource(audioId = "current-audio", modelName = "Current model").write(file)
        val original = TranscriptStore(file)
        val exposed = texts.migrateWorking(original, TranscriptSource("old-audio", "legacy-text", "Old model", "legacy-model"))
        try {
            assertSame(original, exposed)
            assertEquals(TranscriptSource("current-audio", "legacy-text", "Current model", "legacy-model"), exposed.source)
            CacheArtifacts.deleteWhenReleased(file)
            CacheArtifacts.deleteWhenReleased(TranscriptSource.metadata(file))
            assertTrue("Successful transfer must retain its text lease", file.isFile)
            assertTrue(TranscriptSource.metadata(file).isFile)
        } finally { exposed.keepForRecovery() }
        assertFalse(file.exists())
        assertFalse(TranscriptSource.metadata(file).exists())
    }
}
