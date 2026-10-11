package io.github.lrq3000.utterlane.archive

import java.io.File
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedArtifactCleanupTest {
    @get:Rule val temp = TemporaryFolder()
    private val root by lazy { temp.newFolder("journal") }
    private val journal by lazy { ArchivalJobStore(root) }
    private val owner = ArtifactOwner(ArtifactKind.AUDIO, "owned-copy")
    private val documents = mutableMapOf<String, File>()
    private var allowed = true
    private val namesQueried = mutableListOf<String>()
    private var interruptedName = ""
    private val provider = object : ExternalArtifacts {
        override fun findExact(folder: String, name: String): String? {
            assertFalse(Thread.holdsLock(journal))
            check(allowed) { "Permission revoked" }
            namesQueried.add(name)
            return if (name == interruptedName) "content://p/d/owned" else null
        }
        override fun create(folder: String, name: String, mime: String): String = error("Cleanup must not create")
        override fun write(uri: String, body: (OutputStream) -> Unit) = error("Cleanup must not write")
        override fun delete(uri: String): Boolean {
            assertFalse(Thread.holdsLock(journal))
            check(allowed) { "Permission revoked" }
            val file = documents[uri] ?: return true
            return !file.exists() || file.delete()
        }
    }
    private fun interrupted(): ArchivalCleanup {
        journal.enqueue(ArchivalRequest(owner, ArchivalOperation.COPY_AUDIO, temp.newFile("source.wav"),
            "content://p/tree/folder", "audio.wav", "audio/wav"))
        val job = checkNotNull(journal.claim())
        interruptedName = job.targetName
        documents["content://p/d/owned"] = temp.newFile("owned")
        documents["content://p/d/unrelated"] = temp.newFile("unrelated")
        journal.deleteOwner(owner)
        journal.releaseExecution(job.id, job.generation)
        return targets(journal, job.id).single()
    }

    @Test fun `restart reconciles only interrupted identity and never unrelated file`() {
        val job = interrupted()
        journal.close()
        val restarted = ArchivalJobStore(root)
        assertTrue(ManagedArtifactCleanup(restarted, provider).run(job) { true })
        assertEquals(listOf(interruptedName), namesQueried)
        assertFalse(documents.getValue("content://p/d/owned").exists())
        assertTrue(documents.getValue("content://p/d/unrelated").exists())
        assertTrue(targets(restarted, job.jobId).isEmpty())
    }

    @Test fun `revoked permission and active leases preserve pending cleanup for retry`() {
        var job = interrupted()
        val cleanup = ManagedArtifactCleanup(journal, provider)
        assertFalse(cleanup.run(job) { false })
        assertTrue(namesQueried.isEmpty())
        allowed = false
        assertThrows(IllegalStateException::class.java) { cleanup.run(job) { true } }
        assertEquals(interruptedName, targets(journal, job.jobId).single().name)
        allowed = true
        job = checkNotNull(journal.cleanup(job.key))
        assertTrue(cleanup.run(job) { true })
        assertTrue(targets(journal, job.jobId).isEmpty())
    }

    @Test fun `already deleted exact target is successful cleanup`() {
        val job = interrupted()
        journal.resolvedCleanup(job.key, "content://p/d/missing")
        assertTrue(ManagedArtifactCleanup(journal, provider).run(checkNotNull(journal.cleanup(job.key))) { true })
        assertTrue(targets(journal, job.jobId).isEmpty())
    }
}
