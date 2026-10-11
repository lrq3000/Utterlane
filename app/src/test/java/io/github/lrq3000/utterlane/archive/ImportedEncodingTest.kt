package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportedEncodingTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun compressedTrackWithWavSuffixNeverQueuesEncoder() {
        val jobs = ArchivalJobStore(temp.newFolder())
        val history = RecordingHistory(temp.newFolder(), jobs)
        val input = byteArrayOf(1, 2, 3, 4)
        val owner = history.importAudioOwned(input.inputStream(), "wav", AudioImportMetadata("audio.wav", "audio/wav",
            1000, 4, "audio/mp4a-latm", true), ImportRetentionChoice.KEEP)
        history.planImportEncoding(owner.entry.id, AudioQuality(compress = true), jobs.currentEpoch())
        assertNull(jobs.claim())
        assertArrayEquals(input, owner.entry.part(0).readBytes())
        owner.lease.close()
    }
    @Test fun onlyRetainedUncompressedTrackQueuesIndependentConversion() {
        val jobs = ArchivalJobStore(temp.newFolder())
        val history = RecordingHistory(temp.newFolder(), jobs)
        val owner = history.importAudioOwned(byteArrayOf(7, 8).inputStream(), "wrong", AudioImportMetadata("audio.wrong", "audio/wav",
            1000, 2, "audio/raw", false), ImportRetentionChoice.KEEP)
        val processing = history.beginProcessing(owner.entry.id)
        history.planImportEncoding(owner.entry.id, AudioQuality(compress = true), jobs.currentEpoch())
        assertEquals(ArchivalOperation.ENCODE_AUDIO, jobs.claim()!!.request.operation)
        assertTrue(history.get(owner.entry.id).processingAttempts.contains(processing.token))
        assertArrayEquals(byteArrayOf(7, 8), owner.entry.part(0).readBytes())
        processing.close(); owner.lease.close()
    }
}
