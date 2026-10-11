package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConversionPlanningRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun unrepairedConversionCannotFallThroughToTheLiveWorker() {
        val history = RecordingHistory(temp.newFolder())
        val recording = history.begin(HistoryRetention.FOREVER)
        recording.append(shortArrayOf(1, 2)); recording.finish(false)
        val original = OriginalAudioSource.capture(history.get(recording.entry.id))
        RollingPcmJournal.create(original.directory, AudioQuality(compress = true))
        var invoked = false
        assertThrows(IllegalStateException::class.java) {
            EncodingInput.dispatch(original.directory, live = { invoked = true }, imported = { _, _, _ -> invoked = true })
        }
        assertFalse(invoked)
    }

    @Test fun firstConversionConfigIsCompleteAndRepairPreservesSnapshotAndCancellation() {
        val history = RecordingHistory(temp.newFolder())
        val recording = history.begin(HistoryRetention.FOREVER)
        recording.append(shortArrayOf(11, 22)); recording.finish(false)
        val original = OriginalAudioSource.capture(history.get(recording.entry.id))
        val quality = AudioQuality(compress = true)
        val spool = EncodingInput.prepareConversion(original, quality)
        val complete = ArchiveDisk.read(spool.config)
        assertEquals("audio-0.wav", complete.getProperty("importSource"))
        assertEquals("1", complete.getProperty("importPcmParts"))
        assertEquals(original.representation, complete.getProperty("sourceRepresentation"))
        assertNotNull(complete.getProperty("representation"))
        spool.append(shortArrayOf(31, 41)); spool.preservePcm("User stopped encoding")
        val journal = File(original.directory, "audio.frames").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        AudioMetadata.write(spool.config, java.util.Properties().apply { putAll(complete); remove("importSource") })
        val repaired = EncodingInput.prepareConversion(original, AudioQuality(true, SavedAudioFormat.THREE_GP, 8000))
        assertEquals(quality, repaired.quality)
        assertFalse(repaired.state.encodingAllowed)
        assertEquals(complete.getProperty("representation"), ArchiveDisk.read(repaired.config).getProperty("representation"))
        assertArrayEquals(byteArrayOf(9, 8, 7), journal.readBytes())
        assertArrayEquals(shortArrayOf(31, 41), repaired.readFrames(0, 2))
        spool.closeCapture()
    }

    @Test fun publicationBetweenSnapshotAndDescriptorInstallLeavesOriginalReadableWithoutRestart() = publicationRace(false)
    @Test fun failedPlannerStillReconcilesItsInstalledDescriptorBeforePrivateUnlink() = publicationRace(true)
    private fun publicationRace(failAfterInstall: Boolean) {
        val jobs = ArchivalJobStore(temp.newFolder())
        val external = temp.newFile("published.wav")
        val uri = "content://qa/exact-original"
        val snapshotTaken = CountDownLatch(1)
        val installAllowed = CountDownLatch(1)
        lateinit var history: RecordingHistory
        val io = object : AudioAssetIO {
            override fun available(part: AudioPart) = part.local.isFile || part.uri == uri
            override fun open(part: AudioPart): java.io.InputStream {
                assertFalse("provider IO must remain outside the publication lock", Thread.holdsLock(history))
                return if (part.readUri == uri) external.inputStream() else part.local.inputStream()
            }
        }
        history = RecordingHistory(temp.newFolder(), jobs, io, installOriginal = { snapshot ->
            assertTrue(snapshot.externalParts.isEmpty())
            snapshotTaken.countDown()
            check(installAllowed.await(5, TimeUnit.SECONDS))
            val original = OriginalAudioSource.capture(snapshot)
            if (failAfterInstall) throw java.io.IOException("Interrupted after descriptor installation")
            original
        })
        val pcm = ShortArray(3200) { (it * 19 - 30000).toShort() }
        val recording = history.begin(HistoryRetention.FOREVER)
        recording.append(pcm); recording.finish(false)
        val id = recording.entry.id
        history.planArchive(id, ArchivePlan("content://qa/folder", jobs.currentEpoch()))
        val copy = jobs.claim()!!
        copy.request.source.copyTo(external, overwrite = true)
        jobs.target(copy.id, copy.generation, uri); jobs.ready(copy.id, copy.generation)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val planning = executor.submit { history.planImportEncoding(id, AudioQuality(compress = true), jobs.currentEpoch()) }
            assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS))
            assertNull(OriginalAudioSource.open(recording.entry.directory))
            assertTrue(history.publishArchive(copy))
            assertTrue("planning lease still protects the private WAV", copy.request.source.exists())
            installAllowed.countDown()
            if (failAfterInstall) {
                val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { planning.get(5, TimeUnit.SECONDS) }
                assertTrue(failure.cause is java.io.IOException)
            } else planning.get(5, TimeUnit.SECONDS)
            assertFalse("published private staging is actually released", copy.request.source.exists())
            history.acquire(id).use { lease ->
                lease.originalPcmReader().use { assertArrayEquals(pcm, it.read()) }
                assertEquals(uri, lease.originalSource!!.asset(0).readUri)
            }
        } finally { installAllowed.countDown(); executor.shutdownNow() }
    }

    @Test fun restartAtBareConversionConfigBoundaryDispatchesImportAndReachesEof() {
        val root = temp.newFolder(); val ledger = temp.newFolder()
        val jobs = ArchivalJobStore(ledger)
        val history = RecordingHistory(root, jobs)
        val recording = history.begin(HistoryRetention.FOREVER)
        val pcm = ShortArray(3200) { (it xor 0x3712).toShort() }
        recording.append(pcm); recording.finish(true)
        val original = OriginalAudioSource.capture(history.get(recording.entry.id))
        // Exact pre-fix crash boundary: the durable original exists, but only
        // RollingPcmJournal.create's first replacement reached disk.
        RollingPcmJournal.create(original.directory, AudioQuality(compress = true))
        assertNull(ArchiveDisk.read(File(original.directory, "rolling.properties")).getProperty("importSource"))
        assertEquals(0, jobs.summary().queued)
        jobs.close()
        val restartedJobs = ArchivalJobStore(ledger)
        val restarted = RecordingHistory(root, restartedJobs)
        restarted.initialize()
        restarted.planImportEncoding(recording.entry.id, AudioQuality(compress = true), restartedJobs.currentEpoch())
        val job = restartedJobs.claim()!!
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                EncodingInput.dispatch(job.request.source.parentFile!!,
                    live = { error("Incomplete conversion selected the live encoder and would wait forever") },
                    imported = { file, spool, parts ->
                        assertEquals(original.asset(0).local, file)
                        assertEquals(1, parts)
                        original.pcmReader(PrivateAudioAssetIO).use { reader ->
                            while (reader.offset < original.samples) spool.append(reader.read())
                        }
                        spool.closeCapture()
                        assertTrue(spool.state.closed)
                        assertArrayEquals(pcm, spool.readFrames(0, pcm.size))
                    })
            }.get(5, TimeUnit.SECONDS)
            assertTrue(RollingPcmJournal.open(original.directory).captureClosedOnDisk)
        } finally { executor.shutdownNow() }
    }
}
