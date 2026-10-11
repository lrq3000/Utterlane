package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties

class RepresentationReplacementTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun externalWavIsNotReboundAsM4aAndOldUriRetiresAfterReplacementCopy() = replacement(1)
    @Test fun multipartExternalWavIndicesNeverLeakIntoSingleEncodedReplacement() = replacement(2)

    private fun replacement(parts: Int) {
        val ledger = temp.newFolder()
        val root = temp.newFolder()
        var jobs = ArchivalJobStore(ledger)
        var history = RecordingHistory(root, jobs)
        val entry = legacy(root, parts)
        history.initialize()
        history.planArchive(entry, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        val oldUris = mutableSetOf<String>()
        val oldCopies = mutableListOf<ArchivalJob>()
        repeat(parts) {
            val copy = jobs.claim()!!
            oldCopies += copy
            val uri = "content://qa/old-${copy.request.part}"
            oldUris += uri
            jobs.target(copy.id, copy.generation, uri); jobs.ready(copy.id, copy.generation)
            history.publishArchive(copy)
        }
        assertFalse(history.get(entry).part(0).exists())
        history.setPinned(entry, true, HistoryRetention.FOREVER, "keep")
        history.planImportEncoding(entry, AudioQuality(compress = true), jobs.currentEpoch())
        val encode = jobs.claim()!!
        val directory = history.get(entry).directory
        val frames = history.get(entry).samples
        AudioMetadata.write(File(directory, "capture.properties"), Properties().apply {
            setProperty("frames", frames.toString()); setProperty("closed", "true")
        })
        val output = File(directory, encode.targetName).apply { writeBytes(byteArrayOf(7, 8, 9)) }
        File(directory, "audio.frames").writeBytes(byteArrayOf(1))
        jobs.target(encode.id, encode.generation, output.toURI().toString()); jobs.ready(encode.id, encode.generation)
        assertTrue(history.publishArchive(encode))
        val encoded = history.get(entry)
        assertEquals(1, encoded.parts)
        assertEquals("audio/mp4", encoded.asset(0).mimeType)
        assertNull("old WAV URI must never wear the new M4A metadata", encoded.asset(0).uri)
        assertTrue(encoded.externalParts.isEmpty())
        assertNotEquals(oldCopies[0].request.representation, encoded.representation)
        assertFalse(history.publishArchive(oldCopies[0]))
        assertThrows(ArchivalCancelledException::class.java) { jobs.enqueue(oldCopies[0].request) }
        assertNull("keep the old external source until its replacement is copied", jobs.nextCleanup())

        jobs.close()
        jobs = ArchivalJobStore(ledger); history = RecordingHistory(root, jobs); history.initialize()
        assertNull(history.get(entry).asset(0).uri)
        val copy = jobs.claim()!!
        assertEquals(ArchivalOperation.COPY_AUDIO, copy.request.operation)
        assertEquals(output, copy.request.source)
        assertEquals("audio/mp4", copy.request.mimeType)
        val replacement = "content://qa/new-m4a"
        jobs.target(copy.id, copy.generation, replacement); jobs.ready(copy.id, copy.generation)
        val reader = history.acquire(entry)
        assertTrue(history.publishArchive(copy))
        assertFalse(history.cleanupPublishedEncoding())
        assertNull(jobs.nextCleanup())
        reader.close()
        while (history.cleanupPublishedEncoding()) { }
        while (jobs.maintain()) { }
        jobs.close(); jobs = ArchivalJobStore(ledger)
        val retired = mutableSetOf<String>()
        while (true) {
            val ref = jobs.nextCleanup() ?: break
            retired += checkNotNull(ref.uri)
            jobs.completeCleanup(ref.key)
        }
        assertEquals(oldUris, retired)
        assertFalse(retired.contains(replacement))
        assertFalse(output.exists()) // the new private output follows only its own URI binding
        jobs.close()
        val restartedJobs = ArchivalJobStore(ledger)
        val restarted = RecordingHistory(root, restartedJobs)
        restarted.initialize()
        assertEquals(replacement, restarted.get(entry).asset(0).readUri)
        assertEquals("audio/mp4", restarted.get(entry).asset(0).mimeType)
        assertTrue(restarted.get(entry).pinned)
    }

    private fun legacy(root: File, parts: Int): String {
        val id = "original-$parts"
        val dir = File(root, id).apply { mkdir() }
        val samples = if (parts == 1) 3200L else RecordingHistory.PART_SAMPLES + 3200
        HistoryMetadata.write(File(dir, "recording.properties"), Properties().apply {
            setProperty("started", "1"); setProperty("reference", "1"); setProperty("samples", samples.toString())
            setProperty("status", "saved"); setProperty("mime", "audio/wav")
        })
        repeat(parts) { part ->
            val count = minOf(RecordingHistory.PART_SAMPLES, samples - part * RecordingHistory.PART_SAMPLES)
            val file = File(dir, "audio-$part.wav")
            RandomAccessFile(file, "rw").use { it.setLength(44 + count * 2) }
            WavFile.repair(file)
        }
        return id
    }
}
