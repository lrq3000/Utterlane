package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties

class OriginalMultipartProcessingTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun failedLegacyCaptureRetryUsesExactOriginalAcrossPartBoundaryAfterLossyConversion() = checkOriginal(false)
    @Test fun restartUpgradesAnOlderConvertedCaptureAndStillPrefersItsOriginalParts() = checkOriginal(true)
    private fun checkOriginal(restart: Boolean) {
        val root = temp.newFolder(); val dir = File(root, "legacy").apply { mkdir() }
        val total = RecordingHistory.PART_SAMPLES + 6
        HistoryMetadata.write(File(dir, "recording.properties"), Properties().apply {
            setProperty("started", "1"); setProperty("reference", "1"); setProperty("samples", total.toString())
            setProperty("status", "failed"); setProperty("recovery", "true"); setProperty("mime", "audio/wav")
        })
        val first = File(dir, "audio-0.wav"); val second = File(dir, "audio-1.wav")
        RandomAccessFile(first, "rw").use { it.setLength(44 + RecordingHistory.PART_SAMPLES * 2) }
        RandomAccessFile(second, "rw").use { it.setLength(44 + 12) }
        WavFile.repair(first); WavFile.repair(second)
        fun marker(file: File, frame: Long, values: ShortArray) = RandomAccessFile(file, "rw").use { raw ->
            raw.seek(44 + frame * 2)
            for (sample in values) { raw.write(sample.toInt() and 255); raw.write(sample.toInt() shr 8 and 255) }
        }
        marker(first, RecordingHistory.PART_SAMPLES - 2, shortArrayOf(731, -910))
        marker(second, 0, shortArrayOf(1234, -4321, 32767, -32768, 50, 91))
        val jobs = ArchivalJobStore(temp.newFolder()); var history = RecordingHistory(root, jobs)
        history.initialize()
        var lease = history.acquire("legacy") // taken BEFORE conversion planning / model preparation
        history.setPinned("legacy", true, HistoryRetention.FOREVER, "keep")
        history.planImportEncoding("legacy", AudioQuality(compress = true), jobs.currentEpoch())
        val encode = jobs.claim()!!
        AudioMetadata.write(File(dir, "capture.properties"), Properties().apply { setProperty("frames", total.toString()); setProperty("closed", "true") })
        AudioMetadata.write(File(dir, "preservation.properties"), Properties().apply { setProperty("reclaimed", RecordingHistory.PART_SAMPLES.toString()) })
        WavFile(File(dir, "capture-120.wav")).use { it.append(ShortArray(6) { -3 }) } // derivative, not original
        File(dir, "audio.frames").writeBytes(byteArrayOf(1))
        val lossy = File(dir, encode.targetName).apply { writeBytes(byteArrayOf(7, 8, 9)) }
        jobs.target(encode.id, encode.generation, lossy.toURI().toString()); jobs.ready(encode.id, encode.generation)
        history.publishArchive(encode)
        if (restart) {
            lease.close()
            assertTrue(File(dir, "original-source.properties").delete()) // pre-descriptor schema
            history = RecordingHistory(root, jobs); history.initialize(); lease = history.acquire("legacy")
        }
        val attempt = history.beginProcessing("legacy")
        val source = checkNotNull(lease.originalSource) { "Lost the original multipart descriptor after conversion" }
        assertTrue(source.nativePcm)
        assertEquals(2, source.parts)
        lease.originalPcmReader(RecordingHistory.PART_SAMPLES - 2).use {
            assertArrayEquals(shortArrayOf(731, -910, 1234, -4321, 32767, -32768, 50, 91), it.read())
            assertEquals(total, it.offset)
        }
        assertFalse(history.cleanupPublishedEncoding())
        attempt.complete(); lease.close()
        while (history.cleanupPublishedEncoding()) { }
        history.acquire("legacy").use { assertNull(it.originalSource) }
        assertFalse(first.exists()); assertFalse(second.exists()); assertTrue(lossy.exists())
    }
}
