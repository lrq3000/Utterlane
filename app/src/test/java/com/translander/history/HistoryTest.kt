package com.translander.history

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class HistoryTest {
    @Test fun sharedSnapshotSurvivesPruningOriginalAudio() {
        val directory = Files.createTempDirectory("history-share").toFile()
        try {
            val history = RecordingHistory(File(directory, "history"))
            val recording = history.begin(HistoryRetention.HOUR)!!
            recording.append(shortArrayOf(1, 2, 3))
            recording.finish(false)
            val exported = HistoryExports.create(history, recording.entry.id, File(directory, "exports"))
            history.prune(HistoryRetention.NONE)
            assertFalse(recording.entry.directory.exists())
            assertEquals(1, exported.size)
            assertArrayEquals(shortArrayOf(1, 2, 3), WavFile.read(exported.single(), 0, 3))
        } finally { directory.deleteRecursively() }
    }
    @Test fun hourlyPartsKeepSampleOwnershipAcrossRollover() {
        val directory = Files.createTempDirectory("history-parts").toFile()
        try {
            val history = RecordingHistory(directory)
            val recording = history.begin(HistoryRetention.FOREVER)!!
            val block = ShortArray(3200) { 7 }
            repeat((RecordingHistory.PART_SAMPLES / block.size).toInt()) { recording.append(block) }
            recording.append(shortArrayOf(12, 34))
            recording.finish(false)
            val entry = history.list().single()
            assertEquals(2, entry.parts)
            assertEquals(44 + RecordingHistory.PART_SAMPLES * 2, entry.part(0).length())
            assertEquals(48L, entry.part(1).length())
            assertArrayEquals(shortArrayOf(7, 12, 34), history.read(entry.id, RecordingHistory.PART_SAMPLES - 1, 3))
        } finally { directory.deleteRecursively() }
    }

    @Test fun unfinishedWavIsRecoveredAsInterrupted() {
        val directory = Files.createTempDirectory("history-recovery").toFile()
        try {
            val first = RecordingHistory(directory) { 1000L }
            val recording = first.begin(HistoryRetention.FOREVER)!!
            recording.append(shortArrayOf(1, 2, 3))
            val lastWrite = recording.entry.part(0).lastModified()
            val recovered = RecordingHistory(directory) { 5000L }
            recovered.initialize()
            val entry = recovered.list().single()
            assertEquals("interrupted", entry.status)
            assertEquals(lastWrite, entry.reference)
            assertEquals(3L, entry.samples)
            assertArrayEquals(shortArrayOf(1, 2, 3), recovered.read(entry.id, 0, 10))
            recording.finish(true) // Close the original handle after simulating process recovery.
        } finally { directory.deleteRecursively() }
    }
    @Test fun disabledHistoryCreatesNoAudioAndLeasesDeferPruning() {
        val directory = Files.createTempDirectory("history-test").toFile()
        var now = 1000L
        val history = RecordingHistory(directory) { now }
        try {
            history.initialize()
            assertNull(history.begin(HistoryRetention.NONE))
            assertTrue(directory.listFiles()!!.isEmpty())
            val recording = history.begin(HistoryRetention.HOUR)!!
            recording.append(shortArrayOf(1, 2, 3))
            recording.finish(false)
            val entry = history.list().single()
            val lease = history.acquire(entry.id)
            now += HistoryRetention.HOUR.millis
            history.prune(HistoryRetention.HOUR)
            assertTrue(entry.directory.exists())
            assertTrue(history.list().isEmpty())
            lease.close()
            assertFalse(entry.directory.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedAudioIsRecoveredWithoutChangingItsExpiryReference() {
        val directory = Files.createTempDirectory("interrupted-test").toFile()
        try {
            val history = RecordingHistory(directory) { 1000L }
            history.initialize()
            val recording = history.begin(HistoryRetention.FOREVER)!!
            recording.append(shortArrayOf(1, 2, 3))
            recording.finish(true)
            val entry = history.list().single()
            assertEquals(3L, entry.samples)
            assertEquals("failed", entry.status)
            assertArrayEquals(shortArrayOf(1, 2, 3), history.read(entry.id, 0, 10))
        } finally { directory.deleteRecursively() }
    }
    @Test fun retentionChoicesHaveExactDeadlines() {
        assertEquals(8, HistoryRetention.entries.size)
        for (retention in HistoryRetention.entries) {
            if (retention == HistoryRetention.FOREVER) {
                assertFalse(retention.expired(0, Long.MAX_VALUE))
            } else if (retention == HistoryRetention.NONE) {
                assertTrue(retention.expired(1000, 1000))
            } else {
                assertFalse(retention.expired(1000, 1000 + retention.millis - 1))
                assertTrue(retention.expired(1000, 1000 + retention.millis))
                assertFalse(retention.expired(1000, 999))
            }
        }
        assertEquals(HistoryRetention.NONE, HistoryRetention.fromKey("unknown"))
    }

    @Test fun wavReadUsesSampleOffsetsAndHeaderIsRecoverable() {
        val directory = Files.createTempDirectory("wav-test").toFile()
        try {
            val file = File(directory, "audio.wav")
            WavFile(file).use { wav ->
                wav.append(shortArrayOf(-32768, 0, 32767))
                wav.append(shortArrayOf(12, 34))
                assertArrayEquals(shortArrayOf(0, 32767, 12), wav.read(1, 3))
            }
            assertEquals(54L, file.length())
            RandomAccessFile(file, "rw").use { it.seek(40); it.writeInt(0) }
            WavFile.repair(file)
            RandomAccessFile(file, "r").use {
                it.seek(40)
                assertEquals(10, Integer.reverseBytes(it.readInt()))
            }
        } finally { directory.deleteRecursively() }
    }
}
