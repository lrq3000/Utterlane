package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RollingRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun restartAfterCodecPublicationKeepsAbsoluteCountOfRemovedPcmPrefix() {
        val root = temp.newFolder()
        val journal = ArchivalJobStore(temp.newFolder())
        val history = RecordingHistory(root, journal)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        history.planEncoding(recording.entry.id, journal.currentEpoch())
        repeat(310) { recording.append(ShortArray(3200) { 31 }) }
        val spool = recording.rolling!!
        File(spool.directory, "audio.frames").writeBytes(byteArrayOf(1))
        spool.checkpointEncoded(960000, 1); spool.commitAsr(992000, 0)
        assertEquals(2, spool.reclaim())
        recording.closeCapture()
        val job = journal.claim()!!
        val output = File(spool.directory, job.targetName).apply { writeBytes(byteArrayOf(1)) }
        journal.target(job.id, job.generation, output.toURI().toString()); journal.ready(job.id, job.generation)
        history.publishArchive(job)
        // The primary ASR owner has not finalized: recording.properties is active.
        val restarted = RecordingHistory(root, journal)
        restarted.initialize()
        assertEquals(992000L, restarted.get(recording.entry.id).samples)
        assertEquals("interrupted", restarted.get(recording.entry.id).status)
        assertTrue(spool.pcm(2).exists())
    }
    @Test fun restartRecoversAnEndedCaptureWhoseEofMetadataWriteFailed() {
        val root = temp.newFolder()
        val recording = RecordingHistory(root).begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        recording.append(ShortArray(3200) { 91 })
        val obstruction = File(recording.entry.directory, "capture.properties.tmp").apply { mkdir() }
        assertThrows(java.io.IOException::class.java) { recording.closeCapture() }
        recording.finish(true)
        assertTrue(obstruction.delete())
        val restarted = RecordingHistory(root)
        restarted.initialize()
        val entry = restarted.get(recording.entry.id)
        assertEquals(200L, entry.durationMs)
        assertTrue(RollingPcmJournal.open(entry.directory).state.closed)
        assertFalse(RollingPcmJournal.open(entry.directory).state.encodingAllowed)
        assertEquals(3200L, entry.samples)
    }
    @Test fun restartAtExactPartBoundaryRepairsTheLastUnclosedHeaderOnRead() {
        val root = temp.newFolder()
        val original = RecordingHistory(root).begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        original.append(ShortArray(480000) { 17 })
        val restarted = RecordingHistory(root)
        restarted.initialize()
        val entry = restarted.get(original.entry.id)
        assertEquals(480000L, entry.samples)
        restarted.openPart(entry, 0).use { input ->
            val header = ByteArray(44); assertEquals(44, input.read(header))
            assertEquals(960000, ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        }
        original.closeCapture()
    }
    @Test fun stoppedMixedLayoutPartitionsOneClockWithoutDuplicatePrefix() {
        val root = temp.newFolder()
        val spool = RollingPcmJournal.create(root, AudioQuality(compress = true), 1)
        spool.append(ShortArray(48007) { (it * 5).toShort() })
        File(root, "audio.frames").writeBytes(ByteArray(100))
        spool.checkpointEncoded(32000, 100)
        spool.commitAsr(48007, 0)
        assertEquals(2, spool.reclaim())
        spool.preservePcm("cancelled"); spool.closeCapture()
        val layout = RollingRecovery.layout(RollingPcmJournal.open(root))
        assertEquals(3, layout.parts)
        assertEquals(32000L, layout.asset(root, 0).sourceFrames)
        assertEquals(32000L, layout.asset(root, 1).sourceStartFrame)
        assertEquals(48000L, layout.asset(root, 2).sourceStartFrame)
        assertEquals(48007L, (0 until layout.parts).sumOf { layout.asset(root, it).sourceFrames!! })
    }
}
