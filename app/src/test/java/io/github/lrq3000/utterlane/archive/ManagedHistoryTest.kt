package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import java.io.File
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedHistoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val journal by lazy { ArchivalJobStore(File(temp.root, "journal")) }
    private val external by lazy { temp.newFolder("provider") }
    private val io = object : AudioAssetIO {
        override fun available(part: AudioPart) = if (part.uri == null) part.local.isFile else File(external, "bytes").isFile
        override fun open(part: AudioPart): InputStream = if (part.uri == null) part.local.inputStream() else File(external, "bytes").inputStream()
    }
    private fun ready(owner: ArtifactOwner, file: File): ArchivalJob {
        journal.enqueue(ArchivalRequest(owner, if (owner.kind == ArtifactKind.AUDIO) ArchivalOperation.COPY_AUDIO else ArchivalOperation.SAVE_MARKDOWN,
            file, "content://provider/tree/opaque", file.name, "application/octet-stream"))
        val job = checkNotNull(journal.claim())
        journal.target(job.id, job.generation, "content://provider/document/opaque%2Fid")
        journal.ready(job.id, job.generation)
        return checkNotNull(journal.job(job.id))
    }

    @Test fun `published audio replaces private bytes only after old reader lease releases`() {
        val root = temp.newFolder()
        val history = RecordingHistory(root, journal, io)
        val capture = history.begin(HistoryRetention.FOREVER)
        capture.append(shortArrayOf(3, 8, -100)); capture.finish(false)
        val entry = history.get(capture.entry.id)
        val reader = history.openReader(entry.id)
        entry.part(0).copyTo(File(external, "bytes"))
        val job = ready(ArtifactOwner(ArtifactKind.AUDIO, entry.id), entry.part(0))
        assertTrue(history.publishArchive(job))
        assertTrue(entry.part(0).exists())
        assertArrayEquals(shortArrayOf(3, 8), reader.read(2))
        reader.close()
        assertFalse(entry.part(0).exists())
        val restored = RecordingHistory(root, journal, io)
        restored.initialize()
        assertEquals(job.uri, restored.get(entry.id).asset(0).uri)
        assertTrue(restored.available(restored.get(entry.id)))
        restored.openReader(entry.id, 1).use { assertArrayEquals(shortArrayOf(8, -100), it.read(2)) }
        val exported = HistoryExports.create(restored, entry.id, temp.newFolder()).single()
        assertArrayEquals(File(external, "bytes").readBytes(), exported.readBytes())
    }

    @Test fun `delete and prune keep linked external cleanup even after rows are removed`() {
        val history = TranscriptHistory(temp.newFolder(), journal) { 1000 }
        val source = temp.newFile("complete.txt").apply { writeText("Speaker 1: faithful\n\nSpeaker 2: text") }
        val entry = history.save(source, "actual model")
        val job = ready(ArtifactOwner(ArtifactKind.TRANSCRIPT, entry.id), entry.file)
        assertTrue(history.publishArchive(job))
        history.prune(HistoryRetention.NONE)
        assertNull(history.find(entry.id))
        settle(journal); journal.close()
        val restored = ArchivalJobStore(File(temp.root, "journal"))
        assertEquals(setOf(job.uri), targets(restored, job.id).map { it.uri }.toSet())
        assertThrows(IllegalStateException::class.java) { restored.enqueue(job.request) }
    }

    @Test fun `delete before completion wins and pinned transcript survives pruning`() {
        val history = TranscriptHistory(temp.newFolder(), journal)
        val source = temp.newFile("source.txt").apply { writeText("words") }
        val entry = history.save(source, "model", pinned = true)
        val job = ready(ArtifactOwner(ArtifactKind.TRANSCRIPT, entry.id), entry.file)
        history.prune(HistoryRetention.NONE)
        assertNotNull(history.find(entry.id))
        history.delete(entry.id)
        assertFalse(history.publishArchive(job))
        assertEquals(setOf(job.uri), targets(journal, job.id).map { it.uri }.toSet())
    }

    @Test fun `audio deletion tombstones target but preserves transcript owner`() {
        val audio = RecordingHistory(temp.newFolder(), journal)
        val source = audio.importAudioOwned(byteArrayOf(1, 2).inputStream(), "ogg", AudioImportMetadata(), ImportRetentionChoice.KEEP)
        val text = TranscriptHistory(temp.newFolder(), journal)
        val entry = text.save(temp.newFile("words.txt").apply { writeText("text") }, "model", source.entry.id)
        val job = ready(ArtifactOwner(ArtifactKind.TRANSCRIPT, entry.id), entry.file)
        text.publishArchive(job)
        audio.delete(source.entry.id)
        source.lease.close()
        assertNotNull(text.find(entry.id))
        assertEquals(job.uri, publications(journal, job.request.owner).single().uri)
    }

    @Test fun `captured destination is durable before finalization and is not changed by later settings`() {
        val root = temp.newFolder()
        val audio = RecordingHistory(root, journal)
        val capture = audio.begin(HistoryRetention.FOREVER)
        val plan = ArchivePlan("content://p/tree/original", journal.currentEpoch())
        audio.planArchive(capture.entry.id, plan)
        capture.append(shortArrayOf(1, 2, 3))
        capture.finish(false)
        assertEquals(plan.folder, checkNotNull(journal.claim()).request.destination)
        assertEquals(plan, RecordingHistory(root, journal).list().single().archivePlan)
    }

    @Test fun `text plan survives restart without duplicating job and cancellation does not reenqueue`() {
        val root = temp.newFolder()
        val texts = TranscriptHistory(root, journal)
        val entry = texts.save(temp.newFile("body.txt").apply { writeText("complete body") }, "Actual model")
        val plan = ArchivePlan("content://p/tree/texts", journal.currentEpoch())
        texts.planArchive(entry.id, plan)
        assertEquals(1, journal.summary().queued)
        TranscriptHistory(root, journal).initialize()
        assertEquals(1, journal.summary().queued)
        journal.cancelAll()
        TranscriptHistory(root, journal).initialize()
        settle(journal)
        assertEquals(0, journal.summary().queued)
        assertEquals(0, journal.storageDiagnostics().executionRecords)
    }

    @Test fun `cancel racing publication does not tombstone the surviving history owner`() {
        val text = TranscriptHistory(temp.newFolder(), journal)
        val audio = RecordingHistory(temp.newFolder(), journal)
        val entry = text.save(temp.newFile("text.txt").apply { writeText("words") }, "model")
        val owner = ArtifactOwner(ArtifactKind.TRANSCRIPT, entry.id)
        val job = ready(owner, entry.file)
        journal.cancelAll()
        ArchivalPublication(journal, audio, text).finish(job)
        assertFalse(journal.isDeleted(owner))
        assertTrue(entry.file.isFile)
        journal.enqueue(job.request, journal.currentEpoch())
        assertNotNull(journal.claim())
    }

    @Test fun `archival journal failure cannot undo a complete text history publication`() {
        val text = TranscriptHistory(temp.newFolder(), journal)
        val epoch = journal.currentEpoch()
        val root = File(temp.root, "journal")
        check(root.deleteRecursively()); root.writeText("Simulated journal IO failure")
        val entry = text.save(temp.newFile("transcript.txt").apply { writeText("Useful completed text") }, "actual model",
            archivePlan = ArchivePlan("content://p/tree/folder", epoch))
        assertEquals("Useful completed text", entry.file.readText())
        assertNotNull(text.find(entry.id))
    }

    @Test fun `archival journal failure cannot turn capture completion into failure`() {
        val audio = RecordingHistory(temp.newFolder(), journal)
        val capture = audio.begin(HistoryRetention.FOREVER)
        audio.planArchive(capture.entry.id, ArchivePlan("content://p/tree/folder", journal.currentEpoch()))
        capture.append(shortArrayOf(3, 8, 9))
        val root = File(temp.root, "journal")
        check(root.deleteRecursively()); root.writeText("Simulated journal IO failure")
        capture.finish(false)
        assertEquals("saved", audio.get(capture.entry.id).status)
        assertArrayEquals(shortArrayOf(3, 8, 9), audio.read(capture.entry.id, 0, 3))
    }

    @Test fun `pinning an older audio owner keeps its original destination snapshot`() {
        val audio = RecordingHistory(temp.newFolder(), journal)
        val capture = audio.begin(HistoryRetention.FOREVER)
        audio.planArchive(capture.entry.id, ArchivePlan("content://p/tree/first", journal.currentEpoch()))
        capture.append(shortArrayOf(1)); capture.finish(false)
        audio.planArchive(capture.entry.id, ArchivePlan("content://p/tree/later-setting", journal.currentEpoch()))
        assertEquals(1, journal.summary().queued)
        assertEquals("content://p/tree/first", checkNotNull(journal.claim()).request.destination)
        assertEquals("content://p/tree/first", audio.get(capture.entry.id).archivePlan?.folder)
    }

    @Test fun `known container MIME gives pending name a compatible extension without converting input`() {
        val original = temp.newFile("misnamed.mp3")
        WavFile(original).use { it.append(shortArrayOf(1, -2, 3)) }
        val audio = RecordingHistory(temp.newFolder(), journal)
        val metadata = AudioImportMetadata("misnamed.mp3", "audio/wav", trackMimeType = "audio/raw", isCompressed = false)
        val owned = original.inputStream().use { audio.importAudioOwned(it, "mp3", metadata, ImportRetentionChoice.KEEP) }
        try {
            audio.planArchive(owned.entry.id, ArchivePlan("content://p/tree/folder", journal.currentEpoch()))
            assertTrue(checkNotNull(journal.claim()).targetName.endsWith(".wav"))
            assertArrayEquals(original.readBytes(), owned.entry.part(0).readBytes())
            assertEquals(metadata, audio.get(owned.entry.id).importMetadata)
        } finally { owned.lease.close() }
    }

    @Test fun `new recognition reader keeps using leased private input after external publication`() {
        // No external adapter is available, equivalent to a provider disappearing
        // while model preparation still owns the useful private input.
        val audio = RecordingHistory(temp.newFolder(), journal)
        val capture = audio.begin(HistoryRetention.FOREVER)
        capture.append(shortArrayOf(7, 8, 9)); capture.finish(false)
        audio.acquire(capture.entry.id).use {
            val entry = audio.get(capture.entry.id)
            val job = ready(ArtifactOwner(ArtifactKind.AUDIO, entry.id), entry.part(0))
            assertTrue(audio.publishArchive(job))
            audio.openReader(entry.id).use { reader -> assertArrayEquals(shortArrayOf(7, 8, 9), reader.read(3)) }
            assertEquals(job.uri, audio.get(entry.id).asset(0).uri)
        }
    }

    @Test fun `last live owner release wakes deferred cleanup without deleting retained private input`() {
        val audio = RecordingHistory(temp.newFolder(), journal)
        val owned = audio.importAudioOwned(byteArrayOf(1, 2, 3).inputStream(), "ogg", AudioImportMetadata(), ImportRetentionChoice.KEEP)
        audio.planArchive(owned.entry.id, ArchivePlan("content://p/tree/folder", journal.currentEpoch()))
        val job = checkNotNull(journal.claim())
        journal.target(job.id, job.generation, "content://p/d/partial")
        journal.pause(job.id, job.generation, "Provider unavailable")
        journal.releaseExecution(job.id, job.generation)
        journal.deferCleanupForLease(checkNotNull(journal.nextCleanup()).key)
        val before = journal.revision.value
        audio.acquire(owned.entry.id).close()
        assertEquals(before, journal.revision.value)
        owned.lease.close()
        assertTrue(journal.revision.value > before)
        assertTrue(audio.get(owned.entry.id).part(0).isFile)
    }
}
