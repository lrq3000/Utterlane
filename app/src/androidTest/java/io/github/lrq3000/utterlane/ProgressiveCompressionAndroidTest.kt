package io.github.lrq3000.utterlane

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lrq3000.utterlane.archive.*
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** Model-free codec evidence. Run alone: the isolation case stops only this app's
 * archive worker, and all tests clean only their own generated fixture folders. */
@RunWith(AndroidJUnit4::class)
class ProgressiveCompressionAndroidTest {
    @Test fun aacAlignedAndShortSourcesPreserveBothEdgesAfterEos() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "aac-eos-qa-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val reference = PcmPrimingEstimator.probe(16000)
            for (frames in listOf(16384, 317)) {
                val directory = File(root, frames.toString()).apply { mkdirs() }
                val spool = RollingPcmJournal.create(directory, AudioQuality(compress = true))
                val pcm = ShortArray(frames) { (reference[it] * 32768).toInt().toShort() }
                spool.append(pcm); spool.closeCapture()
                val output = File(directory, "eos.m4a")
                withContext(Dispatchers.IO) { ProgressiveAudioEncoder.encode(spool, output) }
                val decoded = ArrayList<Short>()
                AudioDecoder(context).decodePart(io.github.lrq3000.utterlane.history.AudioPart(output,
                    mimeType = "audio/mp4", pcmSamples = frames.toLong(), sourceFrames = frames.toLong())) { decoded.addAll(it.toList()) }
                assertEquals("codec padding must not extend source duration", frames.toLong(), spool.state.frames)
                assertEquals(frames, decoded.size)
                for (start in listOf(0, frames - 200)) {
                    var xy = 0.0; var xx = 0.0; var yy = 0.0
                    for (i in start until start + 200) {
                        val x = pcm[i].toDouble(); val y = decoded[i].toDouble()
                        xy += x * y; xx += x * x; yy += y * y
                    }
                    assertTrue("$frames-frame source edge at $start must contain the original signal", xy / kotlin.math.sqrt(xx * yy) > .6)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedConversionDescriptorReplansToImportAndCompletesInWorker() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.filesDir, "conversion-plan-qa-${UUID.randomUUID()}").apply { mkdirs() }
        val client = ArchiveWorkerClient(context)
        var jobs = ArchivalJobStore(File(root, "jobs"))
        try {
            var history = io.github.lrq3000.utterlane.history.RecordingHistory(File(root, "history"), jobs)
            val recording = history.begin(io.github.lrq3000.utterlane.history.HistoryRetention.FOREVER)
            repeat(10) { recording.append(ShortArray(3200) { (sin(it * .17) * 9000).toInt().toShort() }) }
            recording.finish(true)
            val original = OriginalAudioSource.capture(history.get(recording.entry.id))
            // Simulate precisely the old first config replacement, then recreate
            // parent repositories before planning resumes.
            RollingPcmJournal.create(original.directory, AudioQuality(compress = true))
            jobs.close(); jobs = ArchivalJobStore(File(root, "jobs"))
            history = io.github.lrq3000.utterlane.history.RecordingHistory(File(root, "history"), jobs)
            history.initialize()
            history.planImportEncoding(recording.entry.id, AudioQuality(compress = true), jobs.currentEpoch())
            val job = checkNotNull(jobs.claim())
            val lease = history.acquire(recording.entry.id)
            try {
                withTimeout(30000) {
                    client.execute(job) { uri -> withContext(Dispatchers.IO) { jobs.target(job.id, job.generation, uri) } }
                }
                assertTrue(jobs.ready(job.id, job.generation))
                assertTrue(history.publishArchive(job))
                assertTrue(RollingPcmJournal.open(original.directory).captureClosedOnDisk)
                var decoded = 0L
                AudioDecoder(context).decodePart(history.get(recording.entry.id).asset(0)) { decoded += it.size }
                assertEquals(32000L, decoded)
            } finally { lease.close() }
        } finally { withContext(Dispatchers.Main) { client.stopAndKill() }; jobs.close(); root.deleteRecursively() }
    }

    @Test fun aacStereoDeclaresActualTrackFormat() = runBlocking {
        checkFormat(AudioQuality(true, sampleRate = 44100, channels = 2, bitRate = 128000))
    }
    @Test fun amrNbBundledBuffersDeclareActualTrackFormatAndTail() = runBlocking {
        checkFormat(AudioQuality(true, SavedAudioFormat.THREE_GP, 8000))
    }
    @Test fun amrWbBundledBuffersDeclareActualTrackFormatAndTail() = runBlocking {
        checkFormat(AudioQuality(true, SavedAudioFormat.THREE_GP, 16000))
    }
    private suspend fun checkFormat(quality: AudioQuality) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "formats-qa-${UUID.randomUUID()}").apply { mkdirs() }
        try {
                org.junit.Assume.assumeNotNull(AudioEncoderCapabilities.encoder(quality))
                val directory = File(root, "${quality.format}-${quality.sampleRate}").apply { mkdirs() }
                val spool = RollingPcmJournal.create(directory, quality, 1)
                val frames = quality.sampleRate * 2 + 77
                spool.append(ShortArray(frames * quality.channels) { (sin(it * .17) * 8000).toInt().toShort() })
                spool.closeCapture()
                val output = File(directory, "output.${quality.extension}")
                withContext(Dispatchers.IO) { ProgressiveAudioEncoder.encode(spool, output) }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.path)
                    val format = extractor.getTrackFormat(0)
                    assertEquals(quality.codecMime, format.getString(MediaFormat.KEY_MIME))
                    assertEquals(quality.sampleRate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(quality.channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } finally { extractor.release() }
                var decoded = 0L
                val expected = quality.capture.recognitionSamples(frames.toLong())
                AudioDecoder(context).decodePart(io.github.lrq3000.utterlane.history.AudioPart(output,
                    mimeType = quality.containerMime, sampleRate = quality.sampleRate, channels = quality.channels,
                    pcmSamples = expected, sourceFrames = frames.toLong())) { decoded += it.size }
                assertEquals(expected, decoded)
        } finally { root.deleteRecursively() }
    }

    @Test fun isolatedWorkerEncodesBeforeCloseAndStopDoesNotStopPcmWriter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.filesDir, "progressive-worker-qa-${UUID.randomUUID()}").apply { mkdirs() }
        val client = ArchiveWorkerClient(context)
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val spool = RollingPcmJournal.create(root, AudioQuality(compress = true), 1)
            val job = ArchivalJob(UUID.randomUUID().toString(), ArchivalRequest(ArtifactOwner(ArtifactKind.AUDIO, "qa"),
                ArchivalOperation.ENCODE_AUDIO, spool.config, root.toURI().toString(), "result.m4a", "audio/mp4"), generation = 1)
            val task = work.async { client.execute(job) { true } }
            val reference = PcmPrimingEstimator.probe(16000)
            repeat(30) { block -> spool.append(ShortArray(3200) { (reference[(block * 3200 + it) % reference.size] * 32768).toInt().toShort() }) }
            withTimeout(15000) { while (spool.state.encodedFrames < 32000) { if (task.isCompleted) task.await(); delay(20) } }
            assertFalse(spool.state.closed)
            spool.commitAsr(96000, 16000)
            assertTrue(spool.reclaim() > 0)
            val boundary = spool.state.reclaimedFrames
            assertTrue(File(root, "audio.frames").length() >= spool.state.encodedBytes)
            spool.preservePcm("Force stop test")
            withContext(Dispatchers.Main) { client.stopAndKill() }
            task.cancelAndJoin()
            spool.append(shortArrayOf(123, -321))
            assertArrayEquals(shortArrayOf(123, -321), spool.readFrames(96000, 2))
            assertEquals(boundary, spool.state.reclaimedFrames)
            spool.closeCapture()
            val layout = RollingRecovery.layout(spool)
            RollingRecovery.ensurePrefix(root, layout)
            var samples = 0L
            val prefixTail = java.util.ArrayDeque<Short>(200)
            val ending = java.util.ArrayDeque<Short>(2)
            for (part in 0 until layout.parts) AudioDecoder(context).decodePart(layout.asset(root, part)) { block ->
                samples += block.size
                for (sample in block) {
                    if (part == 0) { if (prefixTail.size == 200) prefixTail.removeFirst(); prefixTail.addLast(sample) }
                    if (ending.size == 2) ending.removeFirst(); ending.addLast(sample)
                }
            }
            assertEquals(96002L, samples)
            assertArrayEquals("PCM written after worker stop must survive mixed decoding", shortArrayOf(123, -321), ending.toList().toShortArray())
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            prefixTail.forEachIndexed { n, sample ->
                val x = reference[((boundary - 200 + n) % reference.size).toInt()].toDouble()
                val y = sample / 32768.0
                xy += x * y; xx += x * x; yy += y * y
            }
            assertTrue("reclaimed prefix must retain its final source signal", xy / kotlin.math.sqrt(xx * yy) > .6)
        } finally { withContext(Dispatchers.Main) { client.stopAndKill() }; work.cancel(); root.deleteRecursively() }
    }

    @Test fun uncompressedImportIsIndependentlyTranscodedAndOriginalSurvives() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "import-codec-qa-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = File(root, "source.wav")
            io.github.lrq3000.utterlane.history.WavFile(original).use { wav ->
                repeat(30) { wav.append(ShortArray(3200) { (sin(it * 2 * Math.PI * 440 / 16000) * 12000).toInt().toShort() }) }
            }
            val bytes = original.readBytes()
            val spool = RollingPcmJournal.create(root, AudioQuality(compress = true), 1)
            val output = File(root, "import.m4a")
            withContext(Dispatchers.IO) { ImportedAudioEncoder.encode(context, original, spool, output) }
            assertArrayEquals(bytes, original.readBytes())
            var samples = 0L
            AudioDecoder(context).decodePart(io.github.lrq3000.utterlane.history.AudioPart(output, mimeType = "audio/mp4",
                pcmSamples = 96000, sourceFrames = 96000)) { samples += it.size }
            assertEquals(96000L, samples)
        } finally { root.deleteRecursively() }
    }

    @Test fun codecProducesDurablePacketsBeforeStopAndPreservesTail() = runBlocking { liveAac(null) }
    @Test fun c2SoftwareAacWorksWithoutAnEncoderDelayKey() = runBlocking { liveAac("c2.android.aac.encoder") }
    @Test fun omxSoftwareAacUsesMeasuredPrimingAndPreservesTail() = runBlocking { liveAac("OMX.google.aac.encoder") }
    private suspend fun liveAac(encoderName: String?) {
        if (encoderName != null) org.junit.Assume.assumeTrue("$encoderName is not installed on this target",
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.any { it.name == encoderName })
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "codec-qa-${UUID.randomUUID()}").apply { mkdirs() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val spool = RollingPcmJournal.create(root, AudioQuality(compress = true), 1)
            val output = File(root, "result.m4a")
            val task = executor.submit { ProgressiveAudioEncoder.encode(spool, output, encoderName) }
            val pcm = ShortArray(16000 * 4 + 317) { (sin(it * 2 * Math.PI * (if (it >= 64000) 1500 else 440) / 16000) * 12000).toInt().toShort() }
            for (block in pcm.asList().chunked(3200)) spool.append(block.toShortArray())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (spool.state.encodedFrames < 32000 && System.nanoTime() < deadline) {
                if (task.isDone) task.get()
                Thread.sleep(20)
            }
            assertFalse(spool.state.closed)
            assertTrue("real packets must be fsynced while capture is active", spool.state.encodedFrames >= 32000)
            spool.commitAsr(64000, 16000)
            assertTrue(spool.reclaim() > 0)
            spool.closeCapture()
            task.get(15, TimeUnit.SECONDS)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                val format = extractor.getTrackFormat(0)
                assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
                assertEquals(16000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally { extractor.release() }
            var decoded = 0L
            val tail = java.util.ArrayDeque<Short>(512)
            AudioDecoder(context).decodePart(io.github.lrq3000.utterlane.history.AudioPart(output, mimeType = "audio/mp4",
                pcmSamples = pcm.size.toLong(), sourceFrames = pcm.size.toLong()), { block ->
                decoded += block.size
                for (sample in block) { if (tail.size == 512) tail.removeFirst(); tail.addLast(sample) }
            })
            assertEquals("container must trim priming and the partial final frame", pcm.size.toLong(), decoded)
            val ending = tail.toList().takeLast(200)
            val expected = pcm.takeLast(200)
            val product = ending.indices.sumOf { ending[it].toDouble() * expected[it] }
            val norm = kotlin.math.sqrt(ending.sumOf { it.toDouble() * it } * expected.sumOf { it.toDouble() * it })
            assertTrue("the distinct final 317-frame tone must survive priming/padding trimming", product / norm > .6)
        } finally { executor.shutdownNow(); root.deleteRecursively() }
    }
}
