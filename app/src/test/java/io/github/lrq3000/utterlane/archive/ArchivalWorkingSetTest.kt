package io.github.lrq3000.utterlane.archive

import java.io.File
import java.util.Properties
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivalWorkingSetTest {
    @get:Rule val temp = TemporaryFolder()
    private fun request(index: Int) = ArchivalRequest(ArtifactOwner(ArtifactKind.TRANSCRIPT, "text-$index"),
        ArchivalOperation.SAVE_MARKDOWN, File(temp.root, "source.txt"), "content://p/tree/folder", "transcript.md", "text/markdown")
    private fun publish(store: ArchivalJobStore, request: ArchivalRequest): String {
        val id = store.enqueue(request).id
        val job = checkNotNull(store.claim())
        assertEquals(id, job.id)
        store.target(id, job.generation, "content://p/d/$id")
        store.ready(id, job.generation)
        store.publish(id, job.generation) {}
        return id
    }

    @Test fun `many settled jobs retain bounded cache and no execution descriptors after publication`() {
        val root = temp.newFolder()
        val store = ArchivalJobStore(root)
        repeat(300) { publish(store, request(it)) }
        repeat(300) { store.deleteOwner(ArtifactOwner(ArtifactKind.AUDIO, "deleted-$it")) }
        while (store.maintain()) { }
        val diagnostics = store.storageDiagnostics()
        assertTrue("Settled jobs must not remain resident: $diagnostics", diagnostics.cachedJobs <= 32)
        assertEquals(0, diagnostics.cachedOwners)
        assertEquals(0, diagnostics.executionRecords)
        val restarted = ArchivalJobStore(root)
        assertEquals(0, restarted.summary().queued)
        assertEquals(0, restarted.storageDiagnostics().jobReads)
        val before = restarted.storageDiagnostics()
        repeat(200) { restarted.summary(); assertNull(restarted.claim()) }
        assertEquals(before, restarted.storageDiagnostics())
        val queued = restarted.enqueue(request(999))
        val ready = restarted.storageDiagnostics()
        assertEquals(queued.id, restarted.claim()?.id)
        val after = restarted.storageDiagnostics()
        assertTrue(after.jobReads - ready.jobReads <= 2)
        assertTrue(after.indexVisits - ready.indexVisits <= 2)
        val published = mutableListOf<PublishedArtifact>()
        restarted.forEachPublished(request(121).owner, published::add)
        assertEquals(1, published.size)
        assertTrue(published.single().uri.startsWith("content://p/d/"))
        assertTrue(restarted.isDeleted(ArtifactOwner(ArtifactKind.AUDIO, "deleted-121")))
    }

    @Test fun `retiring execution preserves published authority and deduplication after restart`() {
        val root = temp.newFolder()
        val store = ArchivalJobStore(root)
        val request = request(1)
        val id = publish(store, request)
        assertNull(store.job(id))
        val restarted = ArchivalJobStore(root)
        val artifact = mutableListOf<PublishedArtifact>()
        restarted.forEachPublished(request.owner, artifact::add)
        assertEquals("content://p/d/$id", artifact.single().uri)
        val duplicate = restarted.enqueue(request)
        assertEquals(id, duplicate.id)
        assertEquals(ArchivalState.PUBLISHED, duplicate.state)
        assertNull(restarted.claim())
    }

    @Test fun `cancelled backlog retires in bounded steps without delaying a fresh epoch claim`() {
        val root = temp.newFolder()
        val store = ArchivalJobStore(root)
        val epoch = store.currentEpoch()
        repeat(250) { store.enqueue(request(it), epoch) }
        val before = store.storageDiagnostics()
        store.cancelAll()
        assertTrue(store.storageDiagnostics().jobReads - before.jobReads <= 1)
        val fresh = store.enqueue(request(999), store.currentEpoch())
        val claimBefore = store.storageDiagnostics()
        val active = checkNotNull(store.claim())
        assertEquals(fresh.id, active.id)
        assertTrue(store.storageDiagnostics().indexVisits - claimBefore.indexVisits <= 2)
        store.notCreated(active.id, active.generation)
        store.cancelAll(); store.releaseExecution(active.id, active.generation)
        do {
            val prior = store.storageDiagnostics().jobReads
            val more = store.maintain(8)
            assertTrue(store.storageDiagnostics().jobReads - prior <= 16)
        } while (more)
        assertEquals(0, store.storageDiagnostics().executionRecords)
        store.close()
        val restarted = ArchivalJobStore(root)
        assertEquals(0, restarted.summary().queued)
        assertThrows(ArchivalCancelledException::class.java) { restarted.enqueue(request(1), epoch) }
    }

    @Test fun `published reference survives deletion scheduling crash until exact cleanup acknowledgement`() {
        val root = temp.newFolder()
        val owner = request(1).owner
        var store = ArchivalJobStore(root)
        val ids = (0..2).map { publish(store, request(1).copy(destination = "content://p/tree/$it")) }
        store.deleteOwner(owner)
        assertTrue(store.isDeleted(owner))
        store.maintain(1) // Crash in the middle of scheduling this owner's files.
        store.close(); store = ArchivalJobStore(root)
        assertThrows(IllegalStateException::class.java) { store.enqueue(request(1)) }
        settle(store)
        val uris = mutableSetOf<String>()
        while (true) {
            val target = store.nextCleanup() ?: break
            uris.add(checkNotNull(target.uri))
            // URI authority is still on disk until this exact acknowledgement.
            assertTrue(File(root, "owners/${ArchiveDisk.owner(owner)}/${target.jobId}.pub").isFile)
            store.completeCleanup(target.key)
        }
        assertEquals(ids.map { "content://p/d/$it" }.toSet(), uris)
        assertEquals(0, store.summary().cleanup)
        store.close()
        val restarted = ArchivalJobStore(root)
        assertTrue(restarted.isDeleted(owner))
        assertEquals(0, restarted.storageDiagnostics().executionRecords)
        assertTrue(publications(restarted, owner).isEmpty())
    }

    @Test fun `pending targets are individual disk records and prevent unsafe retirement`() {
        val root = temp.newFolder()
        val store = ArchivalJobStore(root)
        store.enqueue(request(1)); val job = checkNotNull(store.claim())
        store.cancelAll()
        repeat(200) { assertFalse(store.target(job.id, job.generation, "content://p/d/late-$it")) }
        assertTrue(File(root, "work/${job.id}.job").length() < 4096)
        assertTrue(store.storageDiagnostics().cachedJobs <= 1)
        assertNull(store.nextCleanup()) // Worker has not yet acknowledged termination.
        store.releaseExecution(job.id, job.generation)
        var cleaned = 0
        while (true) {
            val target = store.nextCleanup() ?: break
            store.completeCleanup(target.key); cleaned++
        }
        assertEquals(200, cleaned)
        assertNull(store.job(job.id))
        assertEquals(0, store.storageDiagnostics().executionRecords)
    }

    @Test fun `same epoch cancelled slot fences old automatic publication after retirement`() {
        val store = ArchivalJobStore(temp.newFolder())
        val request = request(1)
        val first = store.enqueue(request, store.currentEpoch())
        store.cancel(first.id)
        assertNull(store.job(first.id))
        assertEquals(ArchivalState.CANCELLED, store.enqueue(request, store.currentEpoch()).state)
        assertNull(store.claim())
        val explicit = store.enqueue(request, store.currentEpoch(), retryPaused = true)
        assertNotEquals(first.id, explicit.id)
        assertEquals(explicit.id, store.claim()?.id)
    }

    @Test fun `legacy published jobs migrate to compact authority without losing pending cleanup or tombstones`() {
        val root = temp.newFolder()
        val request = request(1)
        val id = UUID.randomUUID().toString()
        val legacy = ArchivalJob(id, request, epoch = 4, generation = 2, state = ArchivalState.PUBLISHED, uri = "content://p/d/retained")
        ArchiveDisk.write(File(root, "control.properties"), Properties().apply { setProperty("epoch", "5") })
        ArchiveDisk.write(File(root, "$id.job"), ArchiveRecords.job(legacy).apply {
            setProperty("cleanup.count", "1"); setProperty("cleanup.0", "content://p/d/partial")
            setProperty("unresolved.count", "1"); setProperty("unresolved.0", legacy.copy(generation = 1).targetName)
        })
        val deleted = ArtifactOwner(ArtifactKind.AUDIO, request.owner.id)
        ArchiveDisk.write(File(root, "${ArchiveDisk.owner(deleted)}.deleted"), Properties().apply { putOwner(deleted) })
        val store = ArchivalJobStore(root)
        assertEquals("content://p/d/retained", publications(store, request.owner).single().uri)
        assertTrue(store.isDeleted(deleted))
        assertEquals(2, store.summary().cleanup)
        assertFalse(File(root, "$id.job").exists())
        while (true) {
            val target = store.nextCleanup() ?: break
            if (target.uri == null) store.resolvedCleanup(target.key, "content://p/d/old-generation")
            store.completeCleanup(target.key)
        }
        assertNull(store.job(id))
        val slot = ArchiveDisk.read(File(root, "owners/${ArchiveDisk.owner(request.owner)}/$id.pub"))
        assertFalse(slot.containsKey("source")); assertFalse(slot.containsKey("model")); assertFalse(slot.containsKey("created"))
        store.close()
        val restarted = ArchivalJobStore(root)
        restarted.cancelAll(); settle(restarted)
        assertEquals("content://p/d/retained", publications(restarted, request.owner).single().uri)
    }

    @Test fun `publication commit repairs a lost deduplication pointer without losing URI authority`() {
        val root = temp.newFolder()
        val store = ArchivalJobStore(root)
        val request = request(1)
        store.enqueue(request); val job = checkNotNull(store.claim())
        store.target(job.id, job.generation, "content://p/d/committed"); store.ready(job.id, job.generation)
        // Simulate death after the compact authority commit, before updating the
        // pointer or removing the old READY execution descriptor/current target.
        ArchiveCatalog(root).write(job.copy(state = ArchivalState.PUBLISHED, uri = "content://p/d/committed"))
        ArchiveDisk.remove(File(root, "owners/${ArchiveDisk.owner(request.owner)}/${ArchiveDisk.slot(request)}.slot"))
        store.close()
        val restarted = ArchivalJobStore(root)
        assertEquals(job.id, restarted.enqueue(request).id)
        assertNull(restarted.claim())
        assertEquals("content://p/d/committed", publications(restarted, request.owner).single().uri)
        assertNull(restarted.nextCleanup())
        assertNull(restarted.job(job.id))
    }

    @Test fun `legacy duplicate publications each retain independent cleanup authority`() {
        val root = temp.newFolder()
        val request = request(1)
        repeat(2) { n ->
            val legacy = ArchivalJob(UUID.randomUUID().toString(), request, generation = 1,
                state = ArchivalState.PUBLISHED, uri = "content://p/d/retained-$n")
            ArchiveDisk.write(File(root, "${legacy.id}.job"), ArchiveRecords.job(legacy))
        }
        val store = ArchivalJobStore(root)
        assertEquals(2, publications(store, request.owner).size)
        assertEquals(0, store.storageDiagnostics().executionRecords)
        store.deleteOwner(request.owner); settle(store)
        val deleted = mutableSetOf<String>()
        while (true) { val ref = store.nextCleanup() ?: break; deleted.add(checkNotNull(ref.uri)); store.completeCleanup(ref.key) }
        assertEquals(setOf("content://p/d/retained-0", "content://p/d/retained-1"), deleted)
        assertEquals(0, store.summary().cleanup)
    }

    @Test fun `new enqueue survives a crash before replacing an older cancelled slot`() {
        for (global in listOf(false, true)) {
            val root = temp.newFolder()
            val store = ArchivalJobStore(root)
            val request = request(1)
            store.enqueue(request); val old = checkNotNull(store.claim())
            if (global) store.cancelAll() else store.cancel(old.id)
            val slot = File(root, "owners/${ArchiveDisk.owner(request.owner)}/${ArchiveDisk.slot(request)}.slot")
            val previous = ArchiveDisk.read(slot)
            val fresh = store.enqueue(request, store.currentEpoch(), retryPaused = true)
            ArchiveDisk.write(slot, previous) // Descriptor committed, pointer update not yet durable.
            store.close()
            val restarted = ArchivalJobStore(root)
            assertEquals(fresh.id, restarted.enqueue(request, restarted.currentEpoch(), retryPaused = true).id)
            assertEquals(1, restarted.summary().queued)
            assertEquals(fresh.id, restarted.claim()?.id)
            restarted.close()
        }
    }
}
