package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EncodingOwnershipTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun privateLostReceiptCleanupResolvesOnlyTheRecordedTarget() {
        val store = ArchivalJobStore(temp.newFolder())
        val directory = temp.newFolder()
        val request = ArchivalRequest(ArtifactOwner(ArtifactKind.AUDIO, "owner"), ArchivalOperation.ENCODE_AUDIO,
            File(directory, "rolling.properties"), directory.toURI().toString(), "audio.m4a", "audio/mp4")
        store.enqueue(request)
        val job = store.claim()!!
        store.cancelAll(); store.releaseExecution(job.id, job.generation)
        val pending = store.nextCleanup()!!
        assertThrows(IllegalArgumentException::class.java) {
            store.resolvedCleanup(pending.key, File(directory, "unrelated.m4a").toURI().toString())
        }
        store.resolvedCleanup(pending.key, File(directory, job.targetName).toURI().toString())
        store.completeCleanup(pending.key)
        assertEquals(0, store.summary().cleanup)
    }
    @Test fun completedEncoderDoesNotDeleteUnprocessedRecoveryPcm() {
        val store = ArchivalJobStore(temp.newFolder())
        val history = RecordingHistory(temp.newFolder(), store)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        history.planEncoding(recording.entry.id, store.currentEpoch())
        recording.append(ShortArray(3200) { 99 }); recording.finish(true)
        val job = store.claim()!!
        val output = File(recording.entry.directory, job.targetName).apply { writeBytes(byteArrayOf(1)) }
        File(recording.entry.directory, "audio.frames").writeBytes(byteArrayOf(1))
        store.target(job.id, job.generation, output.toURI().toString()); store.ready(job.id, job.generation)
        assertTrue(history.publishArchive(job))
        history.cleanupPublishedEncoding()
        assertTrue("failed original ASR still owns its unprocessed PCM", recording.rolling!!.pcm(0).exists())
        history.beginProcessing(recording.entry.id).complete()
        while (history.cleanupPublishedEncoding()) { }
        assertFalse(recording.rolling.pcm(0).exists())
    }
    @Test fun privateEncodingIsDurableWorkWithoutExternalDestination() {
        val store = ArchivalJobStore(temp.newFolder())
        val history = RecordingHistory(temp.newFolder(), store)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        history.planEncoding(recording.entry.id, store.currentEpoch())
        val job = store.claim()!!
        assertEquals(ArchivalOperation.ENCODE_AUDIO, job.request.operation)
        assertEquals(recording.rolling!!.config, job.request.source)
        store.cancelAll()
        history.stopEncoding(recording.entry.id, "Force stop")
        assertFalse(recording.rolling.state.encodingAllowed)
        assertThrows(ArchivalCancelledException::class.java) { history.planEncoding(recording.entry.id, job.epoch) }
        assertEquals(0, store.summary().queued)
        recording.append(ShortArray(3200) { 17 })
        assertEquals(3200L, recording.writtenSamples)
    }
    @Test fun convertedPublicationDoesNotChangeImportOriginOrProcessingObligations() {
        val store = ArchivalJobStore(temp.newFolder())
        val history = RecordingHistory(temp.newFolder(), store)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        history.planEncoding(recording.entry.id, store.currentEpoch())
        recording.append(ShortArray(3200)); recording.finish(false)
        val job = store.claim()!!
        val output = File(recording.entry.directory, job.targetName).apply { writeBytes(byteArrayOf(1)) }
        File(recording.entry.directory, "audio.frames").writeBytes(byteArrayOf(1))
        assertTrue(store.target(job.id, job.generation, output.toURI().toString()))
        assertTrue(store.ready(job.id, job.generation))
        assertTrue(history.publishArchive(job))
        while (history.cleanupPublishedEncoding()) { }
        val result = history.get(recording.entry.id)
        assertNull(result.sourceName)
        assertEquals("audio/mp4", result.asset(0).mimeType)
        assertTrue(result.rollingAudio!!.complete)
        assertEquals(200L, result.durationMs)
        assertFalse(recording.rolling!!.pcm(0).exists())
    }
}
