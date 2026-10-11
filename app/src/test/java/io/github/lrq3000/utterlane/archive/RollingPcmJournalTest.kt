package io.github.lrq3000.utterlane.archive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RollingPcmJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun reclamationIsBatchedAndEncoderCannotAdvanceAnUnprocessedModel() {
        val spool = RollingPcmJournal.create(temporary.newFolder(), AudioQuality(compress = true), 1)
        repeat(41) { spool.append(ShortArray(16000)) }
        File(spool.directory, "audio.frames").writeBytes(ByteArray(100))
        spool.checkpointEncoded(656000, 100)
        assertEquals(0, spool.reclaim())
        spool.commitAsr(656000, 0)
        assertEquals(32, spool.reclaim())
        assertEquals(512000L, spool.state.reclaimedFrames)
        assertEquals(8, spool.reclaim())
        assertEquals(640000L, spool.state.reclaimedFrames)
        assertTrue(spool.pcm(40).exists())
        spool.closeCapture()
    }
    @Test fun aCheckpointCannotReclaimMissingOrTruncatedJournalBytes() {
        val spool = RollingPcmJournal.create(temporary.newFolder(), AudioQuality(compress = true), 1)
        spool.append(ShortArray(48000))
        spool.commitAsr(48000, 0)
        spool.checkpointEncoded(32000, 100)
        assertEquals(0, spool.reclaim())
        File(spool.directory, "audio.frames").writeBytes(ByteArray(99))
        assertEquals(0, spool.reclaim())
        assertTrue(spool.pcm(0).exists())
    }
    @Test fun bothConsumersAndDurableCheckpointAreRequired() {
        val spool = RollingPcmJournal.create(temporary.newFolder(), AudioQuality(compress = true), partSeconds = 1)
        spool.append(ShortArray(48000) { it.toShort() })
        spool.commitAsr(32000, contextSamples = 16000)
        assertEquals(0, spool.reclaim())
        spool.checkpointEncoded(32000, 100)
        File(spool.directory, "audio.frames").writeBytes(ByteArray(100))
        assertEquals(1, spool.reclaim())
        assertFalse(spool.pcm(0).exists())
        assertTrue(spool.pcm(1).exists())
        spool.commitAsr(48000, contextSamples = 16000)
        assertEquals(1, spool.reclaim())
        assertTrue(spool.pcm(2).exists()) // growing part is never reclaimed
    }
    @Test fun restartReconstructsPrefixAndTailWithoutHolesOrDuplication() {
        val root = temporary.newFolder()
        val spool = RollingPcmJournal.create(root, AudioQuality(compress = true), partSeconds = 1)
        val input = ShortArray(40001) { (it * 11).toShort() }
        spool.append(input)
        spool.commitAsr(40001, 0)
        spool.checkpointEncoded(20000, 71)
        File(spool.directory, "audio.frames").writeBytes(ByteArray(71))
        assertEquals(1, spool.reclaim())
        spool.preservePcm("Encoder cancelled")
        spool.closeCapture()
        val reopened = RollingPcmJournal.open(root)
        assertEquals(16000L, reopened.state.reclaimedFrames)
        assertEquals(40001L, reopened.state.frames)
        assertEquals(71L, reopened.state.encodedBytes)
        assertArrayEquals(input.copyOfRange(16000, input.size), reopened.readFrames(16000, 24001))
        assertFalse(reopened.state.encodingAllowed)
        assertEquals(0, reopened.reclaim())
    }
    @Test fun codecStallDoesNotHoldCaptureOrAlterOriginalPcm() {
        val spool = RollingPcmJournal.create(temporary.newFolder(), AudioQuality(compress = true), partSeconds = 1)
        val input = ShortArray(16000) { (it xor 0x1234).toShort() }
        repeat(9) { spool.append(input); spool.commitAsr((it + 1L) * 16000, 0) }
        assertEquals(0, spool.reclaim())
        assertArrayEquals(input, spool.readFrames(128000, 16000))
        spool.preservePcm("Force stop")
        spool.checkpointEncoded(144000, 123)
        assertEquals(0, spool.reclaim())
        spool.append(shortArrayOf(32767, -32768))
        assertArrayEquals(shortArrayOf(32767, -32768), spool.readFrames(144000, 2))
    }
}
