package io.github.lrq3000.utterlane.archive

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivalJobStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = ArchivalJobStore(File(temp.root, "journal"))
    private val owner = ArtifactOwner(ArtifactKind.TRANSCRIPT, "text-owner")
    private fun request(owner: ArtifactOwner = this.owner, folder: String = "content://provider/tree/a%2Fb") =
        ArchivalRequest(owner, ArchivalOperation.SAVE_MARKDOWN, File(temp.root, "text.txt"), folder,
            "2026-10-10_09-30-15_Parakeet-v3.md", "text/markdown", created = 1234, model = "Parakeet v3")

    @Test fun `restart preserves snapshots and exact URI identity`() {
        val journal = store()
        val queued = journal.enqueue(request())
        val running = checkNotNull(journal.claim())
        val uri = "content://provider/tree/a%2Fb/document/raw%3Aopaque%2Ffile"
        assertTrue(journal.target(running.id, running.generation, uri))
        assertTrue(journal.ready(running.id, running.generation))
        journal.close()
        val recovered = checkNotNull(store().job(queued.id))
        assertEquals(request(), recovered.request)
        assertEquals(uri, recovered.uri)
        assertEquals(ArchivalState.READY, recovered.state)
    }
    @Test fun `deletion before creation rejects enqueue after restart`() {
        store().use { it.deleteOwner(owner) }
        assertThrows(IllegalStateException::class.java) { store().enqueue(request()) }
    }
    @Test fun `delete during write preserves exact cleanup after row disappears`() {
        val journal = store()
        journal.enqueue(request())
        val job = checkNotNull(journal.claim())
        journal.deleteOwner(owner)
        val uri = "content://p/document/created-before-callback"
        assertFalse(journal.target(job.id, job.generation, uri))
        assertFalse(journal.ready(job.id, job.generation))
        assertFalse(journal.publish(job.id, job.generation) { fail("Deleted owner published") })
        assertEquals(uri, targets(journal, job.id).single().uri)
        journal.releaseExecution(job.id, job.generation)
        journal.completeCleanup(targets(journal, job.id).single().key)
        settle(journal)
        assertNull(journal.job(job.id))
        journal.close()
        val restored = store()
        assertEquals(0, restored.summary().cleanup)
        assertTrue(restored.isDeleted(owner))
    }
    @Test fun `cancel queued and running is durable and late completions cannot resurrect`() {
        var journal = store()
        val first = journal.enqueue(request())
        val queued = journal.enqueue(request(ArtifactOwner(ArtifactKind.AUDIO, "other")))
        val running = checkNotNull(journal.claim())
        val unclaimedId = if (running.id == queued.id) first.id else queued.id
        journal.cancelAll(); journal.close(); journal = store()
        assertNull(journal.claim())
        assertNull(journal.job(unclaimedId)) // Safely retired, not a lifetime row.
        assertFalse(journal.target(running.id, running.generation, "content://p/d/late"))
        assertFalse(journal.ready(running.id, running.generation))
        assertEquals(ArchivalState.CANCELLED, journal.job(running.id)?.state)
        val fresh = journal.enqueue(request())
        assertNotEquals(running.id, fresh.id)
        assertEquals(fresh.id, journal.claim()?.id)
    }
    @Test fun `publication is idempotent and audio deletion never deletes linked text`() {
        val journal = store()
        journal.enqueue(request())
        val job = checkNotNull(journal.claim())
        val uri = "content://p/d/text"
        journal.target(job.id, job.generation, uri); journal.ready(job.id, job.generation)
        var publicationCount = 0
        assertTrue(journal.publish(job.id, job.generation) { publicationCount++ })
        assertFalse(journal.publish(job.id, job.generation) { publicationCount++ })
        assertEquals(1, publicationCount)
        assertNull(journal.job(job.id))
        journal.deleteOwner(ArtifactOwner(ArtifactKind.AUDIO, owner.id)); settle(journal)
        assertEquals(uri, publications(journal, owner).single().uri)
        journal.deleteOwner(owner); settle(journal)
        assertEquals(uri, targets(journal, job.id).single().uri)
    }
    @Test fun `restart pauses interrupted generation and retry cannot accept old callback`() {
        val journal = store()
        journal.enqueue(request())
        val old = checkNotNull(journal.claim())
        journal.close()
        val restarted = store()
        assertEquals(ArchivalState.PAUSED, restarted.job(old.id)?.state)
        restarted.retry(old.id)
        val next = checkNotNull(restarted.claim())
        assertTrue(next.generation > old.generation)
        assertNotEquals(old.targetName, next.targetName)
        assertFalse(restarted.target(old.id, old.generation, "content://p/d/abandoned"))
        assertTrue(restarted.target(next.id, next.generation, "content://p/d/new"))
        assertFalse(restarted.ready(old.id, old.generation))
        restarted.ready(next.id, next.generation); restarted.publish(next.id, next.generation) {}
        assertEquals("content://p/d/abandoned", targets(restarted, old.id).single().uri)
        assertEquals("content://p/d/new", publications(restarted, owner).single().uri)
    }
    @Test fun `provider failure is retryable and only one job is claimed`() {
        val journal = store()
        journal.enqueue(request()); journal.enqueue(request(ArtifactOwner(ArtifactKind.TRANSCRIPT, "second")))
        val running = checkNotNull(journal.claim())
        assertNull(journal.claim())
        journal.pause(running.id, running.generation, "Folder permission revoked")
        assertEquals("Folder permission revoked", journal.job(running.id)?.error)
        assertNull(journal.claim()) // A pause marker is not proof the worker stopped.
        journal.releaseExecution(running.id, running.generation)
        assertNotNull(journal.claim())
    }
    @Test fun `cancelled producer cannot enqueue when transcription later finishes`() {
        val journal = store()
        val producer = journal.currentEpoch()
        journal.cancelAll()
        assertThrows(IllegalStateException::class.java) { journal.enqueue(request(), producer) }
        assertNotNull(journal.enqueue(request(), journal.currentEpoch()))
    }
    @Test fun `second target receipt cannot orphan the first target`() {
        val journal = store()
        journal.enqueue(request()); val job = checkNotNull(journal.claim())
        journal.target(job.id, job.generation, "content://p/d/first")
        journal.target(job.id, job.generation, "content://p/d/second")
        assertEquals(setOf("content://p/d/first", "content://p/d/second"), targets(journal, job.id).map { it.uri }.toSet())
    }
    @Test fun `published URI survives crash before history projection and later global cancellation`() {
        val journal = store()
        journal.enqueue(request()); val job = checkNotNull(journal.claim())
        journal.target(job.id, job.generation, "content://p/d/committed"); journal.ready(job.id, job.generation)
        assertThrows(IllegalStateException::class.java) { journal.publish(job.id, job.generation) { error("Simulated process loss") } }
        journal.close()
        val restarted = store()
        restarted.cancelAll(); settle(restarted)
        assertEquals("content://p/d/committed", publications(restarted, owner).single().uri)
        assertNull(restarted.job(job.id))
    }
    @Test fun `failed writer leaves exact partial target queued for cleanup before retry`() {
        val journal = store()
        journal.enqueue(request()); val job = checkNotNull(journal.claim())
        val uri = "content://p/d/partial"
        journal.target(job.id, job.generation, uri); journal.pause(job.id, job.generation, "Disk full during provider close")
        assertEquals(uri, targets(journal, job.id).single().uri)
        assertNull(journal.job(job.id)?.uri)
    }
    @Test fun `definitive precreation failure retires its intent without a recovery folder scan`() {
        val journal = store()
        journal.enqueue(request()); val job = checkNotNull(journal.claim())
        journal.notCreated(job.id, job.generation); journal.pause(job.id, job.generation, "Access denied before createDocument")
        journal.releaseExecution(job.id, job.generation)
        assertTrue(targets(journal, job.id).isEmpty())
        journal.retry(job.id); val retry = checkNotNull(journal.claim())
        journal.notCreated(job.id, job.generation)
        assertEquals(retry.targetName, targets(journal, job.id).single().name)
    }
    @Test fun `restart reads settled metadata without rewriting every historical job`() {
        val journal = store()
        val job = journal.enqueue(request())
        val metadata = File(temp.root, "journal/work/${job.id}.job")
        assertTrue(metadata.setLastModified(1000)); val before = metadata.readBytes()
        journal.close()
        assertEquals(job.id, store().job(job.id)?.id)
        assertEquals(1000L, metadata.lastModified()); assertArrayEquals(before, metadata.readBytes())
    }
    @Test fun `explicit repeat save retries paused work but old producer cannot revive cancellation`() {
        val journal = store(); val producer = journal.currentEpoch()
        val first = journal.enqueue(request(), producer); val running = checkNotNull(journal.claim())
        journal.pause(running.id, running.generation, "Destination unavailable"); journal.releaseExecution(running.id, running.generation)
        assertEquals(ArchivalState.PAUSED, journal.enqueue(request(), producer).state)
        val retry = journal.enqueue(request(), producer, retryPaused = true)
        assertEquals(first.id, retry.id); assertEquals(ArchivalState.QUEUED, retry.state)
        assertTrue(checkNotNull(journal.claim()).generation > running.generation)
        journal.cancelAll()
        assertThrows(ArchivalCancelledException::class.java) { journal.enqueue(request(), producer, retryPaused = true) }
        val fresh = journal.enqueue(request(), journal.currentEpoch(), retryPaused = true)
        assertNotEquals(first.id, fresh.id); assertEquals(ArchivalState.QUEUED, fresh.state)
    }
}
