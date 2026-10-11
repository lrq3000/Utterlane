package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivalConcurrentDeletionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15000) fun `history deletion completes while provider write blocks and original import survives`() {
        val journal = ArchivalJobStore(temp.newFolder())
        val history = RecordingHistory(temp.newFolder(), journal)
        val original = temp.newFile("user-original.ogg").apply { writeBytes(ByteArray(128000) { (it % 127).toByte() }) }
        val owned = original.inputStream().use { history.importAudioOwned(it, "ogg", AudioImportMetadata(), ImportRetentionChoice.KEEP) }
        history.planArchive(owned.entry.id, ArchivePlan("content://p/tree/folder", journal.currentEpoch()))
        val job = checkNotNull(journal.claim())
        val output = temp.newFile("managed")
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val provider = object : ExternalArtifacts {
            override fun findExact(folder: String, name: String): String? = error("Known-URI cleanup must not enumerate")
            override fun create(folder: String, name: String, mime: String) = "content://p/d/owned"
            override fun write(uri: String, body: (OutputStream) -> Unit) {
                assertFalse(Thread.holdsLock(history)); assertFalse(Thread.holdsLock(journal))
                blocked.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                output.outputStream().use(body)
            }
            override fun delete(uri: String): Boolean {
                assertEquals("content://p/d/owned", uri)
                return !output.exists() || output.delete()
            }
        }
        try {
            val copy = pool.submit {
                owned.entry.part(0).inputStream().use { input ->
                    ArchivalTransfer.execute(job, input, owned.entry.part(0).length(), provider,
                        { journal.target(job.id, job.generation, it) })
                }
                assertFalse(journal.ready(job.id, job.generation))
                assertFalse(history.publishArchive(job))
            }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            pool.submit { history.delete(owned.entry.id) }.get(3, TimeUnit.SECONDS)
            assertTrue(history.list().isEmpty())
            assertTrue(owned.entry.part(0).isFile) // Active lease, not logical availability.
            release.countDown(); copy.get(5, TimeUnit.SECONDS)
            journal.releaseExecution(job.id, job.generation)
            owned.lease.close()
            assertFalse(owned.entry.directory.exists())
            assertTrue(ManagedArtifactCleanup(journal, provider).run(checkNotNull(journal.nextCleanup())) { !history.hasLeases(it.id) })
            assertFalse(output.exists())
            assertTrue(original.isFile)
            assertEquals(128000L, original.length())
        } finally { release.countDown(); owned.lease.close(); pool.shutdownNow() }
    }
}
