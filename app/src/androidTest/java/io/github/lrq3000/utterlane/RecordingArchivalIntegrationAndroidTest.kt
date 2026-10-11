package io.github.lrq3000.utterlane

import android.app.ActivityManager
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.archive.*
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Production capture/pipeline/coordinator boundaries, with real codecs. Only the
 * acoustic fixture replaces AudioRecord; the paired run uses real worker ASR.
 * Run alone in a QA installation: force-stop intentionally owns its archive queue. */
@RunWith(AndroidJUnit4::class)
class RecordingArchivalIntegrationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val evidence get() = File(app.getExternalFilesDir(null), "recording-qa").apply { mkdirs() }
    private suspend fun until(check: () -> Boolean) = withTimeout(30000) { while (!check()) delay(20) }
    private fun save(name: String, value: JSONObject) { File(evidence, "$name.json").writeText(value.toString(2)) }
    private fun archivePid() = app.getSystemService(ActivityManager::class.java).runningAppProcesses
        .firstOrNull { it.processName == "${app.packageName}:archive" }?.pid
    private fun control(method: String) = app.contentResolver.call(
        DocumentsContract.buildTreeDocumentUri("${app.packageName}.archive-test", "root"), "fixture.$method", null, null)

    /** Restore all preferences touched by these cases, and delete only their new
     * history IDs. Weight installation is explicit and only the standard QA app. */
    private suspend fun configured(block: suspend () -> Unit) {
        val settings = app.settingsRepository
        app.modelManager.initializeSelection()
        val model = app.modelManager.selected.value
        val archival = settings.archival.first()
        val enabled = settings.audioHistoryEnabled.first()
        val retention = settings.audioHistoryRetention.first()
        val textEnabled = settings.transcriptHistoryEnabled.first()
        val diarization = settings.diarizationEnabled.first()
        val dictionary = settings.dictionaryEnabled.first()
        val options = settings.runtimeOptions.first()
        val before = app.recordingHistory.list().map { it.id }.toSet()
        val activity = launchNativeActivity(HistoryActivity.intent(app))
        try {
            settings.setAudioHistoryEnabled(true); settings.setAudioHistoryRetention(HistoryRetention.FOREVER)
            settings.setTranscriptHistoryEnabled(false); settings.setAudioFolder(null)
            settings.setAutomaticMarkdown(false); settings.setDiarizationEnabled(false)
            settings.setDictionaryEnabled(false); settings.setRuntimeOptions(RuntimeOptions())
            block()
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { app.archival.forceStopAll() }
                until { archivePid() == null && app.archival.state.value.producers == 0 }
                app.recordingHistory.list().filter { it.id !in before }.forEach {
                    app.recordingHistory.delete(it.id); RecordingRecovery.dismissNotification(app, it.id)
                }
                app.recognizerManager.selectModel(model)
                settings.setAudioQuality(archival.quality); settings.setAudioFolder(archival.audioFolder)
                settings.setAutomaticMarkdown(archival.automaticMarkdown)
                settings.setAudioHistoryEnabled(enabled); settings.setAudioHistoryRetention(retention)
                settings.setTranscriptHistoryEnabled(textEnabled); settings.setDiarizationEnabled(diarization)
                settings.setDictionaryEnabled(dictionary); settings.setRuntimeOptions(options)
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test fun nativeCaptureSurvivesMissingModelAndForceStopAndStartsDuringBlockedArchive() = runBlocking {
        configured {
            app.recognizerManager.selectModel(ModelCatalog.PARAKEET_V3)
            assertFalse("This case requires the catalog ONNX model to be absent", app.modelManager.isModelReady())
            app.settingsRepository.setAudioQuality(AudioQuality(compress = true))
            val folder = DocumentsContract.buildTreeDocumentUri("${app.packageName}.archive-test", "blocked")
            app.settingsRepository.setAudioFolder(folder.toString())
            control("block")
            try {
                captureMissingModel("native", forceWorkerStop = false)
                until { (control("status")?.getInt("blocked") ?: 0) > 0 }
                val blockedPid = checkNotNull(archivePid())
                // First MicrophoneSession has released admission, although its
                // actual external publication is still hung in another process.
                assertFalse(MicrophoneSession.isBusy())
                captureMissingModel("native-during-finalization", forceWorkerStop = true, blockedPid = blockedPid)
            } finally { control("unblock") }
        }
    }

    @Test fun actualStereoAudioRecordPreservesItsNativeFormatAndNormalizedCount() = runBlocking {
        configured {
            app.recognizerManager.selectModel(ModelCatalog.PARAKEET_V3)
            assertFalse(app.modelManager.isModelReady())
            app.settingsRepository.setAudioQuality(AudioQuality(true, sampleRate = 44100, channels = 2, bitRate = 128000))
            captureMissingModel("stereo", forceWorkerStop = true)
        }
    }

    private suspend fun captureMissingModel(name: String, forceWorkerStop: Boolean, blockedPid: Int? = null) {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val completed = CompletableDeferred<SessionFailure?>()
        val closed = CompletableDeferred<Unit>()
        val capture = MicrophoneSession(app, scope, onText = { _, _ -> },
            onComplete = { _, failure -> completed.complete(failure) }, onSessionClosed = { closed.complete(Unit) },
            openRecoveryOnFailure = false)
        val started = SystemClock.elapsedRealtime()
        try {
            capture.start()
            withTimeout(15000) { capture.metrics.state.first { it.capturedSamples >= 48000 && it.recognitionFailure != null } }
            val id = checkNotNull(capture.audioId)
            val spool = RollingPcmJournal.open(app.recordingHistory.get(id).directory)
            if (blockedPid == null) until { spool.state.encodedFrames > 0 }
            else assertEquals(blockedPid, archivePid())
            val before = spool.state
            assertFalse(completed.isCompleted)
            assertFalse(before.closed)
            assertEquals("Unavailable ASR cannot authorize raw reclamation", 0L, before.reclaimedFrames)
            assertTrue(spool.pcm(0).isFile)
            assertEquals(0L, capture.metrics.state.value.processedSamples)
            var afterStopFrames = 0L
            if (forceWorkerStop) {
                withContext(Dispatchers.Main) { app.archival.forceStopAll() }
                until { !spool.state.encodingAllowed && archivePid() == null }
                val captured = capture.metrics.state.value.capturedSamples
                withTimeout(10000) { capture.metrics.state.first { it.capturedSamples >= captured + 16000 } }
                afterStopFrames = spool.state.frames
                assertFalse(completed.isCompleted)
                assertEquals(0L, spool.state.reclaimedFrames)
            }
            capture.stop()
            val failure = withTimeout(15000) { completed.await() }
            withTimeout(15000) { closed.await() }
            assertEquals(SessionFailure.Kind.MODEL, failure?.kind)
            assertEquals(id, failure?.recoveryId)
            val entry = app.recordingHistory.get(id)
            assertEquals(spool.format.recognitionSamples(spool.state.frames), entry.samples)
            assertTrue(entry.needsRecovery)
            if (forceWorkerStop) {
                var decoded = 0L
                val retained = app.recordingHistory.processingAudio(entry)
                for (part in 0 until retained.parts) AudioDecoder(app).decodePart(retained.asset(part)) { decoded += it.size }
                assertEquals("All retained raw frames must be replayable", entry.samples, decoded)
            }
            save(name, JSONObject().put("source_rate", spool.format.sampleRate).put("source_channels", spool.format.channels)
                .put("native_source", entry.microphone?.source?.name).put("frames_before_stop", before.frames)
                .put("encoded_before_stop", before.encodedFrames).put("reclaimed_before_stop", before.reclaimedFrames)
                .put("frames_after_worker_stop", afterStopFrames).put("final_source_frames", spool.state.frames)
                .put("saved_asr_samples", entry.samples).put("captured_metric_samples", capture.metrics.state.value.capturedSamples)
                .put("failure", failure?.kind?.name).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("started_during_blocked_archive", blockedPid != null))
            if (name == "stereo") assumeTrue("AudioRecord rejected 44100 Hz stereo; native fallback verified",
                spool.format == PcmFormat(44100, 2))
        } finally {
            if (!closed.isCompleted) { capture.cancel(); withTimeout(15000) { closed.await() } }
            scope.cancel()
        }
    }

    @Test fun delayedAndSlowConsumerReadsOriginalPcmWhileCodecAdvances() = runBlocking {
        configured {
            app.settingsRepository.setAudioQuality(AudioQuality(compress = true))
            val producer = app.archival.producer(userInitiated = true)
            val recording = app.recordingHistory.begin(HistoryRetention.FOREVER, quality = AudioQuality(compress = true))
            val spool = checkNotNull(recording.rolling)
            assertTrue(producer.encode(recording.entry.id))
            val ready = CompletableDeferred<Unit>()
            val source = PatternCapture()
            var accepted = 0L
            val task = async(Dispatchers.IO) {
                RecordingPipeline(source, app.recordingHistory, recording, RuntimeOptions()).run(
                    prepare = { ready.await() }, accept = { block ->
                        block.forEachIndexed { i, value -> assertEquals(pattern(accepted + i), value) }
                        accepted += block.size
                        spool.commitAsr(accepted, 16000)
                        delay(5) // Consumer remains deliberately slower than capture.
                    }, finish = {})
            }
            try {
                until { spool.state.frames > spool.partFrames && spool.state.encodedFrames > 16000 }
                val gated = spool.state
                assertEquals(0L, accepted)
                assertEquals(0L, gated.reclaimedFrames)
                assertTrue(spool.pcm(0).isFile && spool.pcm(1).isFile)
                ready.complete(Unit)
                until { accepted >= 32000 }
                val lag = recording.writtenSamples - accepted
                assertTrue(lag > 0)
                source.stop()
                val outcome = withTimeout(30000) { task.await() }
                assertNull(outcome.captureError); assertNull(outcome.storageError); assertNull(outcome.processingError)
                assertEquals(source.frames, accepted)
                assertEquals(source.frames, recording.writtenSamples)
                recording.finish(false)
                save("slow-consumer", JSONObject().put("gated_frames", gated.frames)
                    .put("encoded_while_gated", gated.encodedFrames).put("reclaimed_while_gated", gated.reclaimedFrames)
                    .put("backlog_with_slow_consumer", lag).put("source_frames", source.frames).put("accepted_frames", accepted)
                    .put("every_original_sample_equal", true))
            } finally { source.stop(); ready.complete(Unit); task.cancelAndJoin(); recording.finish(true); producer.close() }
        }
    }

    @Test fun pairedRealModelFixturePreservesPcmAndReferenceQuality() = runBlocking {
        assumeTrue("Opt in with -e recordingFixture true and stage the local fixture/model",
            InstrumentationRegistry.getArguments().getString("recordingFixture") == "true")
        assertEquals("Keep the .dhome identity model-free", "io.github.lrq3000.utterlane", app.packageName)
        configured {
            app.recognizerManager.selectModel(ModelCatalog.DEFAULT)
            val weights = File(app.modelManager.directory().apply { mkdirs() }, "model.gguf")
            if (!weights.exists()) File(evidence, "model.gguf").copyTo(weights)
            assertTrue("ModelManager must checksum-verify catalog weights", app.modelManager.ensureVerified())
            val pcm = File(app.cacheDir, "recording-pair.wav")
            try {
                WavFile(pcm).use { wav -> AudioDecoder(app).decode(File(evidence, "test-1-speaker-french.m4a").path, { wav.append(it) }) }
                val sourceSamples = (pcm.length() - 44) / 2
                val on = pairedRun(pcm, true)
                val off = pairedRun(pcm, false)
                val pair = JSONObject().put("model", ModelCatalog.DEFAULT.id)
                    .put("order", "ON then OFF")
                    .put("source_samples", sourceSamples).put("options", JSONObject(RuntimeOptions().toMap()))
                    .put("off", off).put("on", on)
                    .put("exact_transcripts_equal", off.getString("transcript") == on.getString("transcript"))
                    .put("native_control_insertion_ceiling", 2)
                save("paired", pair) // Keep both actual results even if a fidelity assertion fails.
                assertPairFidelity(pair, File(evidence, "test-1-speaker-french_transcript_true-diarization.txt").readText())
            } finally { pcm.delete() }
        }
    }

    /** Revalidate the new quality gate against saved REAL inference, without
     * rerunning a timing pair. Also prove it rejects missing words/corrupt input. */
    @Test fun savedPairSatisfiesNativeControlQualityEnvelope() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recordingEvidence") == "true")
        assertEquals("io.github.lrq3000.utterlane", app.packageName)
        val pair = JSONObject(File(evidence, "control-reference.json").readText())
        val reference = File(evidence, "test-1-speaker-french_transcript_true-diarization.txt").readText()
        val control = JSONObject(File(evidence, "native-replay-control.json").readText())
        assertTrue(control.getBoolean("complete"))
        val replies = control.getJSONArray("replies")
        val firstWindowVariants = (0 until replies.length()).map(replies::getJSONObject)
            .filter { it.getInt("window") == 0 }.map { it.getString("owned_text") }.toSet()
        assertEquals("This disposition requires observed archival-free variation", 2, firstWindowVariants.size)
        val firstWindowTokens = (0 until replies.length()).map(replies::getJSONObject)
            .filter { it.getInt("window") == 0 }.map { it.getJSONArray("tokens").toString() }.toSet()
        val originalTokens = listOf("off", "on").map {
            pair.getJSONObject(it).getJSONArray("native_windows").getJSONObject(0).getJSONArray("tokens").toString()
        }.toSet()
        assertEquals("Control must reproduce BOTH original native token variants", originalTokens, firstWindowTokens)
        for (i in 0 until replies.length()) assertTrue(replies.getJSONObject(i).getBoolean("archive_absent"))
        assertPairFidelity(pair, reference)
        for (mutation in listOf("tail", "pcm", "extra_words")) {
            val altered = JSONObject(pair.toString())
            val on = altered.getJSONObject("on")
            when (mutation) {
                "tail" -> on.put("transcript", on.getString("transcript").removeSuffix(" ou pas"))
                "pcm" -> on.put("pcm_sha256", "changed input")
                else -> on.put("transcript", "extra invented words " + on.getString("transcript"))
            }
            var rejected = false
            try { assertPairFidelity(altered, reference) } catch (_: AssertionError) { rejected = true }
            assertTrue("Fidelity gate accepted $mutation regression", rejected)
        }
    }

    private fun assertPairFidelity(pair: JSONObject, reference: String) {
        val off = pair.getJSONObject("off")
        val on = pair.getJSONObject("on")
        assertEquals(407186L, pair.getLong("source_samples"))
        assertEquals(off.getJSONArray("window_pcm").toString(), on.getJSONArray("window_pcm").toString())
        fun words(text: String) = Regex("[\\p{L}\\p{N}_]+").findAll(text.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
        val expected = words(reference.trim().removePrefix("Speaker 1:"))
        assertEquals("Use the supplied one-speaker reference", 70, expected.size)
        for (result in listOf(off, on)) {
            assertEquals(407186L, result.getLong("source_samples"))
            assertEquals(407186L, result.getLong("accepted_samples"))
            assertEquals(407186L, result.getLong("processed_samples"))
            assertEquals("dd4c1b5b7d0024e8879fe0436cf5d7e507c64ac166bd59616d3d085e905ec1d9", result.getString("pcm_sha256"))
            val inputWindows = result.getJSONArray("window_pcm")
            val nativeWindows = result.getJSONArray("native_windows")
            assertEquals(inputWindows.length(), nativeWindows.length())
            for (i in 0 until inputWindows.length()) {
                assertEquals(inputWindows.getJSONObject(i).getString("sha256"), nativeWindows.getJSONObject(i).getString("sha256"))
                assertEquals(inputWindows.getJSONObject(i).getInt("samples"), nativeWindows.getJSONObject(i).getInt("samples"))
            }
            val actual = words(result.getString("transcript"))
            // The archival-free SAME-worker replay produced 0 and 2 insertions
            // from identical input. This fixture-specific ceiling permits only
            // that observed native quality envelope: no deleted/substituted
            // reference words, no extra errors, and an exact final 12-word tail.
            var matched = 0
            for (word in actual) if (matched < expected.size && word == expected[matched]) matched++
            assertEquals("Reference words lost or reordered: ${result.getString("transcript")}", expected.size, matched)
            assertTrue("More insertions than the archival-free control", actual.size - expected.size <= 2)
            assertEquals(expected.takeLast(12), actual.takeLast(12))
        }
    }

    private suspend fun pairedRun(pcm: File, compression: Boolean): JSONObject = coroutineScope {
        // Give both arms identical native history: a fresh production worker and
        // its normal one-second silence warmup, all outside the measured interval.
        // Reusing the OFF worker for ON would additionally warm it on this speech.
        app.recognizerManager.selectModel(ModelCatalog.DEFAULT)
        val nativeWindows = JSONArray()
        val manager = RecognizerManager(app, app.modelManager, backendFactory = { model, _ ->
            val worker = WorkerRecognitionBackend(app, model)
            object : RecognitionBackend by worker {
                override fun transcribeWindow(samples: ShortArray): WindowResult {
                    val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(samples)
                    return worker.transcribeWindow(samples).also { result ->
                        nativeWindows.put(JSONObject().put("samples", samples.size)
                            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes.array())
                                .joinToString("") { "%02x".format(it) })
                            .put("tokens", JSONArray(result.tokens.toList()))
                            .put("timestamps", JSONArray(result.timestamps.toList())))
                    }
                }
            }
        })
        val loading = SystemClock.elapsedRealtime()
        assertTrue(manager.initialize())
        val loadMs = SystemClock.elapsedRealtime() - loading
        val quality = AudioQuality(compress = compression)
        app.settingsRepository.setAudioQuality(quality)
        val producer = app.archival.producer(userInitiated = true)
        val recording = app.recordingHistory.begin(HistoryRetention.FOREVER, quality = quality)
        val source = FileCapture(pcm)
        val digest = MessageDigest.getInstance("SHA-256")
        var accepted = 0L
        var processed = 0L
        val windows = mutableListOf<Long>()
        val windowPcm = JSONArray()
        val probe = AudioSegmenter(options = RuntimeOptions()) { window ->
            val bytes = ByteBuffer.allocate(window.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(window.samples)
            windowPcm.put(JSONObject().put("start", window.startSample).put("owned_start", window.ownedStart)
                .put("owned_end", window.ownedEnd).put("final", window.isFinal)
                .put("samples", window.samples.size).put("sha256", MessageDigest.getInstance("SHA-256")
                    .digest(bytes.array()).joinToString("") { "%02x".format(it) }))
        }
        val transcript = manager.createSession(RuntimeOptions(), onProcessed = { end, ms ->
            processed = end; windows += ms
            recording.rolling?.commitAsr(end, 192000)
        }) {}
        if (compression) assertTrue(producer.encode(recording.entry.id))
        val start = SystemClock.elapsedRealtime()
        var stop = start
        var liveEncoded = 0L
        val stopAtEnd = launch {
            source.delivered.await()
            liveEncoded = recording.rolling?.state?.encodedFrames ?: 0
            stop = SystemClock.elapsedRealtime(); source.stop()
        }
        try {
            val outcome = RecordingPipeline(source, app.recordingHistory, recording, RuntimeOptions()).run(
                prepare = {}, accept = { block ->
                    val bytes = ByteBuffer.allocate(block.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(block); digest.update(bytes.array())
                    accepted += block.size; probe.accept(block); transcript.accept(block)
                }, finish = { probe.finish(); transcript.finish() })
            val completed = SystemClock.elapsedRealtime()
            assertNull(outcome.captureError); assertNull(outcome.storageError); assertNull(outcome.processingError)
            assertEquals(source.hash(), digest.digest().joinToString("") { "%02x".format(it) })
            recording.finish(false)
            if (compression) {
                assertTrue("Production archive worker must advance before capture Stop", liveEncoded > 0)
                until { app.recordingHistory.get(recording.entry.id).rollingAudio?.complete == true }
            }
            val text = transcript.store.file.readText()
            File(evidence, "${if (compression) "on" else "off"}.txt").writeText(text)
            JSONObject().put("compression", compression).put("load_and_warmup_excluded_ms", loadMs)
                .put("source_samples", source.frames).put("accepted_samples", accepted)
                .put("processed_samples", processed).put("pcm_sha256", source.hash()).put("transcript", text)
                .put("window_pcm", windowPcm)
                .put("native_windows", nativeWindows)
                .put("asr_window_ms", JSONArray(windows)).put("asr_total_ms", windows.sum())
                .put("capture_to_result_ms", completed - start).put("stop_to_result_ms", completed - stop)
                .put("capture_to_archive_ms", SystemClock.elapsedRealtime() - start).put("encoded_before_stop", liveEncoded)
        } finally {
            source.stop(); stopAtEnd.cancelAndJoin(); transcript.close(); transcript.store.dispose()
            recording.finish(true); producer.close(); manager.forceUnload()
        }
    }

    private class FileCapture(private val file: File) : AudioCapture {
        private val stopped = AtomicBoolean()
        val delivered = CompletableDeferred<Unit>()
        var frames = 0L; private set
        private val digest = MessageDigest.getInstance("SHA-256")
        private var result = ""
        fun hash() = result
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(44)
                while (!stopped.get() && shouldContinue() && input.filePointer < input.length()) {
                    val bytes = ByteArray(minOf(3200, input.length() - input.filePointer).toInt())
                    input.readFully(bytes); digest.update(bytes)
                    val block = ShortArray(bytes.size / 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(block)
                    onSamples(block); frames += block.size; Thread.sleep(20)
                }
            }
            result = digest.digest().joinToString("") { "%02x".format(it) }
            delivered.complete(Unit)
            while (!stopped.get() && shouldContinue()) Thread.sleep(5)
        }
        override fun stop() { stopped.set(true) }
    }
    private class PatternCapture : AudioCapture {
        private val stopped = AtomicBoolean()
        @Volatile var frames = 0L; private set
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            while (!stopped.get() && shouldContinue()) {
                val block = ShortArray(1600) { pattern(frames + it) }
                onSamples(block); frames += block.size; Thread.sleep(2)
            }
        }
        override fun stop() { stopped.set(true) }
    }
    companion object { private fun pattern(offset: Long) = ((offset * 7919 % 24001) - 12000).toShort() }
}
