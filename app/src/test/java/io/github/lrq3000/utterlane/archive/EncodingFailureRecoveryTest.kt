package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class EncodingFailureRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun normalRetryRepairsPendingFallbackWithoutRestartOrManualCopy() = pendingFallbackRetry(restart = false)
    @Test fun pendingFallbackSurvivesLedgerRestartAndNormalRetry() = pendingFallbackRetry(restart = true)
    private fun pendingFallbackRetry(restart: Boolean) {
        val root = temp.newFolder(); val ledger = temp.newFolder()
        var jobs = ArchivalJobStore(ledger)
        var history = RecordingHistory(root, jobs)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        val id = recording.entry.id
        history.planEncoding(id, jobs.currentEpoch())
        history.planArchive(id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        repeat(6) { recording.append(ShortArray(3200) { 71 }) }
        val spool = recording.rolling!!
        val obstruction = File(spool.directory, "capture.properties.tmp").apply { mkdir() }
        assertThrows(java.io.IOException::class.java) { recording.closeCapture() }
        recording.finish(true)
        val encode = jobs.claim()!!
        assertThrows(java.io.IOException::class.java) {
            EncodingFailureRecovery(jobs, history).afterWorkerStopped(encode, "EOF publication failed")
        }
        jobs.releaseExecution(encode.id, encode.generation)
        assertEquals("fallback must stay visible to ordinary Retry", 1, jobs.summary().paused)
        assertEquals(ArchivalPhase.PENDING_FALLBACK, jobs.job(encode.id)!!.phase)
        assertFalse(spool.state.encodingAllowed)
        assertNull(jobs.claim())
        val cleanupCount = jobs.summary().cleanup
        jobs.retryPaused(); jobs.beginCleanupPass(); while (jobs.maintain()) { }
        val stillBlocked = jobs.claim()!!
        assertEquals(ArchivalPhase.PENDING_FALLBACK, stillBlocked.phase)
        assertThrows(java.io.IOException::class.java) {
            EncodingFailureRecovery(jobs, history).afterWorkerStopped(stillBlocked, "Retry while IO is still blocked")
        }
        jobs.releaseExecution(stillBlocked.id, stillBlocked.generation)
        assertEquals(1, jobs.summary().paused)
        assertEquals("fallback attempts must not accumulate worker targets", cleanupCount, jobs.summary().cleanup)
        if (restart) {
            jobs.close(); jobs = ArchivalJobStore(ledger)
            assertEquals("pending fallback survives journal reconstruction", 1, jobs.summary().paused)
            history = RecordingHistory(root, jobs)
        }
        assertTrue(obstruction.delete())
        // Exactly the coordinator's Retry path. No Keep, new plan or COPY injection.
        jobs.retryPaused(); jobs.beginCleanupPass(); while (jobs.maintain()) { }
        val pending = jobs.claim()!!
        assertEquals(encode.id, pending.id)
        assertEquals(ArchivalPhase.PENDING_FALLBACK, pending.phase)
        assertNull("parent fallback must not create a worker target", jobs.cleanup("${pending.id}~${ArchiveDisk.key("${pending.id}:${pending.generation}")}"))
        if (restart) history.initialize()
        EncodingFailureRecovery(jobs, history).afterWorkerStopped(pending, "Retry retained fallback")
        jobs.releaseExecution(pending.id, pending.generation)
        assertTrue(RollingPcmJournal.open(spool.directory).captureClosedOnDisk)
        assertEquals(19200L, RollingPcmJournal.open(spool.directory).state.frames)
        val copy = jobs.claim()!!
        assertEquals(ArchivalOperation.COPY_AUDIO, copy.request.operation)
        assertEquals(19200L, history.get(id).rollingAudio!!.frames)
        history.preparePart(history.get(id), copy.request.part)
        val saved = File(temp.newFolder(), "fallback.wav")
        copy.request.source.copyTo(saved)
        assertEquals(44L + 19200 * 2, saved.length())
        jobs.target(copy.id, copy.generation, "content://qa/published"); jobs.ready(copy.id, copy.generation)
        assertTrue(history.publishArchive(copy))
        assertFalse(spool.pcm(0).exists())
        assertEquals(0, jobs.summary().paused)
        jobs.retryPaused(); jobs.beginCleanupPass(); while (jobs.maintain()) { }
        assertNull("Retry must never restart the stopped codec", jobs.claim())
    }

    @Test fun forceStopCancelsPendingFallbackAndRejectsClaimedLateRetry() = forceStopPendingFallback(claimed = true)
    @Test fun forceStopCancelsQueuedFallbackBeforeRetryCanRun() = forceStopPendingFallback(claimed = false)
    private fun forceStopPendingFallback(claimed: Boolean) {
        val jobs = ArchivalJobStore(temp.newFolder()); val history = RecordingHistory(temp.newFolder(), jobs)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        val id = recording.entry.id
        history.planEncoding(id, jobs.currentEpoch())
        history.planArchive(id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        recording.append(ShortArray(3200))
        val obstruction = File(recording.entry.directory, "capture.properties.tmp").apply { mkdir() }
        assertThrows(java.io.IOException::class.java) { recording.closeCapture() }
        recording.finish(true)
        val encode = jobs.claim()!!
        assertThrows(java.io.IOException::class.java) {
            EncodingFailureRecovery(jobs, history).afterWorkerStopped(encode, "EOF publication failed")
        }
        jobs.releaseExecution(encode.id, encode.generation)
        assertEquals(1, jobs.summary().paused)
        jobs.retryPaused(); jobs.beginCleanupPass(); while (jobs.maintain()) { }
        val pending = if (claimed) jobs.claim()!! else encode
        jobs.cancelAll()
        assertTrue(obstruction.delete())
        EncodingFailureRecovery(jobs, history).afterWorkerStopped(pending, "late retry")
        jobs.releaseExecution(pending.id, pending.generation)
        jobs.retryPaused(); jobs.beginCleanupPass(); while (jobs.maintain()) { }
        assertNull(jobs.claim())
        assertEquals(0, jobs.summary().paused)
        assertTrue(recording.rolling!!.pcm(0).exists())
        assertFalse(recording.rolling.state.encodingAllowed)
        assertTrue(history.get(id).externalParts.isEmpty())
    }

    @Test fun activeCaptureFailureStaysPendingUntilNormalFinishCommitsItsCopies() {
        val jobs = ArchivalJobStore(temp.newFolder()); val history = RecordingHistory(temp.newFolder(), jobs)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        val id = recording.entry.id
        history.planEncoding(id, jobs.currentEpoch())
        history.planArchive(id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        recording.append(ShortArray(3200))
        val encode = jobs.claim()!!
        EncodingFailureRecovery(jobs, history).afterWorkerStopped(encode, "codec failed while capturing")
        jobs.releaseExecution(encode.id, encode.generation)
        assertEquals(1, jobs.summary().paused)
        assertEquals(ArchivalPhase.PENDING_FALLBACK, jobs.job(encode.id)!!.phase)
        assertNull(jobs.claim())
        recording.append(ShortArray(16000))
        recording.finish(false)
        assertEquals(0, jobs.summary().paused)
        assertEquals(19200L, recording.rolling!!.state.frames)
        assertTrue(recording.rolling.captureClosedOnDisk)
        val copy = jobs.claim()!!
        assertEquals(ArchivalOperation.COPY_AUDIO, copy.request.operation)
        assertTrue(jobs.job(encode.id)?.state in setOf(null, ArchivalState.CANCELLED))
    }

    @Test fun copyQueueWriteFailureKeepsFallbackRetryableUntilAllPartsAreScheduled() {
        val ledger = temp.newFolder(); val jobs = ArchivalJobStore(ledger)
        val history = RecordingHistory(temp.newFolder(), jobs)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        val id = recording.entry.id
        history.planEncoding(id, jobs.currentEpoch())
        history.planArchive(id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        recording.append(ShortArray(483200) { 71 }); recording.finish(false)
        val encode = jobs.claim()!!
        // Fail the derived queue index after its COPY descriptor reaches disk.
        val obstruction = File(ledger, "index/QUEUED-${jobs.currentEpoch()}").apply { writeText("blocked") }
        assertThrows(IllegalStateException::class.java) {
            EncodingFailureRecovery(jobs, history).afterWorkerStopped(encode, "container failed")
        }
        assertTrue(obstruction.delete())
        jobs.releaseExecution(encode.id, encode.generation)
        assertEquals(1, jobs.summary().paused)
        jobs.retryPaused(); jobs.beginCleanupPass(); while (jobs.maintain()) { }
        val copied = mutableSetOf<Int>()
        while (true) {
            val next = jobs.claim() ?: break
            if (next.id == encode.id) {
                assertEquals(ArchivalPhase.PENDING_FALLBACK, next.phase)
                EncodingFailureRecovery(jobs, history).afterWorkerStopped(next, "Retry retained fallback")
                jobs.releaseExecution(next.id, next.generation)
            } else {
                assertEquals(ArchivalOperation.COPY_AUDIO, next.request.operation)
                assertTrue("committed COPY must be deduplicated", copied.add(next.request.part))
                jobs.target(next.id, next.generation, "content://qa/published-${next.request.part}")
                jobs.ready(next.id, next.generation); assertTrue(history.publishArchive(next))
            }
        }
        assertEquals(setOf(0, 1), copied)
        assertEquals(0, jobs.summary().paused)
    }

    @Test fun failedEofWatermarkKeepsAll19200FramesThroughFallbackPublicationAndRestart() = eofFailure(19200)
    @Test fun failedFirstEofWatermarkKeepsShortCaptureThroughFallbackPublicationAndRestart() = eofFailure(3200)
    @Test fun failedEofRepairPreservesReclaimedEncodedPrefixAndEveryRawTailFrame() = eofFailure(19200, prefixFrames = 480000)
    @Test fun unrepairedClosureBlocksFallbackPublicationAndPrivateUnlink() {
        val jobs = ArchivalJobStore(temp.newFolder()); val history = RecordingHistory(temp.newFolder(), jobs)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        val id = recording.entry.id
        history.planEncoding(id, jobs.currentEpoch())
        history.planArchive(id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        repeat(6) { recording.append(ShortArray(3200) { 71 }) }
        val spool = recording.rolling!!
        val obstruction = File(spool.directory, "capture.properties.tmp").apply { mkdir() }
        assertThrows(java.io.IOException::class.java) { recording.closeCapture() }
        recording.finish(true)
        val encode = jobs.claim()!!
        assertThrows(java.io.IOException::class.java) {
            EncodingFailureRecovery(jobs, history).afterWorkerStopped(encode, "EOF publication failed")
        }
        jobs.releaseExecution(encode.id, encode.generation)
        assertNull(jobs.claim())
        assertEquals(19200L, history.get(id).rollingAudio!!.frames)
        assertTrue(spool.pcm(0).exists())

        // A transfer already queued by an older build must pass the same gate at
        // publication; a successful provider write is not sufficient authority.
        jobs.enqueue(ArchivalRequest(ArtifactOwner(ArtifactKind.AUDIO, id), ArchivalOperation.COPY_AUDIO,
            spool.pcm(0), "content://qa/folder", "fallback.wav", "audio/wav"), jobs.currentEpoch())
        val copy = jobs.claim()!!
        jobs.target(copy.id, copy.generation, "content://qa/published"); jobs.ready(copy.id, copy.generation)
        assertThrows(java.io.IOException::class.java) { history.publishArchive(copy) }
        assertTrue(history.get(id).externalParts.isEmpty())
        assertTrue(spool.pcm(0).exists())
        assertTrue(obstruction.delete())
        assertTrue(history.publishArchive(copy))
        assertEquals(19200L, RollingPcmJournal.open(spool.directory).state.frames)
        assertTrue(RollingPcmJournal.open(spool.directory).captureClosedOnDisk)
        assertFalse(spool.pcm(0).exists())
    }

    private fun eofFailure(tailFrames: Int, prefixFrames: Int = 0) {
        val root = temp.newFolder(); val ledger = temp.newFolder(); val folder = temp.newFolder()
        val copies = mutableMapOf<String, File>()
        val io = object : AudioAssetIO {
            override fun available(part: AudioPart) = part.local.isFile || part.uri in copies
            override fun open(part: AudioPart): java.io.InputStream =
                part.readUri?.let { copies.getValue(it).inputStream() } ?: part.local.inputStream()
        }
        var jobs = ArchivalJobStore(ledger)
        var history = RecordingHistory(root, jobs, io)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        val id = recording.entry.id
        history.planEncoding(id, jobs.currentEpoch())
        history.planArchive(id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        val samples = ShortArray(prefixFrames + tailFrames) { (it * 29 - 32767).toShort() }
        for (start in samples.indices step 3200) recording.append(samples.copyOfRange(start, minOf(start + 3200, samples.size)))
        val spool = recording.rolling!!
        val encoded = File(spool.directory, "audio.frames")
        if (prefixFrames > 0) {
            val bytes = EncodedJournal.Writer(encoded).use { writer ->
                repeat((prefixFrames + 1023) / 1024) { writer.append(byteArrayOf(1), it * 64000L, 1024) }
                writer.checkpoint()
            }
            AudioMetadata.write(File(spool.directory, "codec.properties"), Properties().apply {
                setProperty("delay", "0"); setProperty("config", java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x14, 0x08)))
            })
            spool.checkpointEncoded(prefixFrames.toLong(), bytes); spool.commitAsr(samples.size.toLong(), 0)
            assertEquals(1, spool.reclaim())
        } else encoded.writeBytes(byteArrayOf(9, 8, 7))
        val encodedBefore = encoded.readBytes()
        val periodic = RollingPcmJournal.open(spool.directory).state.frames
        assertTrue(periodic < samples.size)
        if (prefixFrames == 0) assertEquals(if (tailFrames == 19200) 16000L else 0L, periodic)
        val obstruction = File(spool.directory, "capture.properties.tmp").apply { mkdir() }
        assertThrows(java.io.IOException::class.java) { recording.closeCapture() }
        recording.finish(true)
        assertEquals(samples.size.toLong(), history.get(id).rollingAudio!!.frames)
        assertTrue(obstruction.delete())

        val lease = history.acquire(id)
        val encode = jobs.claim()!!
        EncodingFailureRecovery(jobs, history).afterWorkerStopped(encode, "Final capture metadata failed")
        jobs.releaseExecution(encode.id, encode.generation)
        assertEquals("fallback must retain the finalized writer count", samples.size.toLong(), history.get(id).rollingAudio!!.frames)
        val repaired = RollingPcmJournal.open(spool.directory).state
        assertTrue("closure commits before any fallback can publish", repaired.closed)
        assertEquals(samples.size.toLong(), repaired.frames)
        assertEquals(prefixFrames.toLong(), repaired.reclaimedFrames)
        assertArrayEquals(encodedBefore, encoded.readBytes())
        var count = 0
        while (true) {
            val copy = jobs.claim() ?: break
            assertEquals(ArchivalOperation.COPY_AUDIO, copy.request.operation)
            history.preparePart(history.get(id), copy.request.part)
            val target = File(folder, "part-${copy.request.part}.${history.get(id).asset(copy.request.part).archiveExtension}")
            copy.request.source.copyTo(target)
            val uri = "content://qa/published-${copy.request.part}"
            copies[uri] = target
            jobs.target(copy.id, copy.generation, uri); jobs.ready(copy.id, copy.generation)
            assertTrue(history.publishArchive(copy)); count++
        }
        assertEquals(if (prefixFrames > 0) 2 else 1, count)
        val rawTail = spool.pcm(prefixFrames / spool.partFrames)
        assertTrue(rawTail.exists()); lease.close(); assertFalse(rawTail.exists())
        jobs.close(); jobs = ArchivalJobStore(ledger)
        history = RecordingHistory(root, jobs, io); history.initialize()
        val restored = history.get(id)
        assertEquals(samples.size.toLong(), restored.samples)
        assertEquals(samples.size.toLong(), restored.rollingAudio!!.frames)
        val tail = restored.asset(restored.parts - 1)
        assertEquals(tailFrames.toLong(), tail.sourceFrames)
        assertEquals(tailFrames.toLong(), tail.pcmSamples)
        history.acquire(id).use {
            history.openPart(restored, restored.parts - 1).use { input ->
                val header = ByteArray(44); java.io.DataInputStream(input).readFully(header)
                val bytes = ByteArray(tailFrames * 2); java.io.DataInputStream(input).readFully(bytes)
                val actual = ShortArray(tailFrames)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(actual)
                assertArrayEquals(samples.copyOfRange(prefixFrames, samples.size), actual)
                assertEquals(-1, input.read())
            }
        }
        assertArrayEquals(encodedBefore, encoded.readBytes())
    }
    @Test fun failedFinalizationAfterCaptureCloseCopiesRetainedPcmWithoutRestart() = fallback(mixed = false)
    @Test fun failedContainerAfterReclamationCopiesMixedPrefixAndTail() = fallback(mixed = true)
    private fun fallback(mixed: Boolean) {
        val jobs = ArchivalJobStore(temp.newFolder())
        val history = RecordingHistory(temp.newFolder(), jobs)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        history.planEncoding(recording.entry.id, jobs.currentEpoch())
        history.planArchive(recording.entry.id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        val spool = recording.rolling!!
        recording.append(ShortArray(if (mixed) 960007 else 3200) { (it * 7).toShort() })
        if (mixed) {
            val bytes = EncodedJournal.Writer(File(spool.directory, "audio.frames")).use { writer ->
                repeat(938) { writer.append(byteArrayOf(1), it * 64000L, 1024) }; writer.checkpoint()
            }
            AudioMetadata.write(File(spool.directory, "codec.properties"), Properties().apply {
                setProperty("delay", "0"); setProperty("config", java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x14, 0x08)))
            })
            spool.checkpointEncoded(960000, bytes); spool.commitAsr(960007, 0)
            assertEquals(2, spool.reclaim())
        }
        recording.finish(false)
        val encode = jobs.claim()!!
        assertEquals(ArchivalOperation.ENCODE_AUDIO, encode.request.operation)
        EncodingFailureRecovery(jobs, history).afterWorkerStopped(encode, "EOS/container failure")
        jobs.releaseExecution(encode.id, encode.generation)
        jobs.retryPaused(); while (jobs.maintain()) { }
        val copies = mutableListOf<ArchivalJob>()
        while (true) {
            val copy = jobs.claim() ?: break
            assertEquals("invalid encoder attempts must not be retried", ArchivalOperation.COPY_AUDIO, copy.request.operation)
            val entry = history.get(recording.entry.id)
            history.preparePart(entry, copy.request.part)
            assertTrue(copy.request.source.isFile)
            assertEquals(entry.asset(copy.request.part).mimeType, copy.request.mimeType)
            jobs.target(copy.id, copy.generation, "content://qa/fallback-${copy.request.part}"); jobs.ready(copy.id, copy.generation)
            assertTrue(history.publishArchive(copy)); copies += copy
        }
        assertEquals(if (mixed) 2 else 1, copies.size)
        assertEquals(if (mixed) setOf("audio/mp4", "audio/wav") else setOf("audio/wav"), copies.map { it.request.mimeType }.toSet())
        assertEquals(copies.size, history.get(recording.entry.id).externalParts.size)
        assertEquals(0, jobs.summary().paused)
    }
    @Test fun explicitStopEpochPreventsLateFailureFromEnqueueingFallback() {
        val jobs = ArchivalJobStore(temp.newFolder()); val history = RecordingHistory(temp.newFolder(), jobs)
        val recording = history.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
        history.planEncoding(recording.entry.id, jobs.currentEpoch())
        history.planArchive(recording.entry.id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        recording.append(ShortArray(3200)); recording.finish(false)
        val encode = jobs.claim()!!
        jobs.cancelAll(); history.stopEncoding(recording.entry.id, "User stop")
        EncodingFailureRecovery(jobs, history).afterWorkerStopped(encode, "late codec failure")
        jobs.releaseExecution(encode.id, encode.generation)
        jobs.retryPaused(); while (jobs.maintain()) { }
        assertNull(jobs.claim()); assertEquals(0, jobs.summary().queued)
        assertTrue(recording.rolling!!.pcm(0).exists())
    }
}
