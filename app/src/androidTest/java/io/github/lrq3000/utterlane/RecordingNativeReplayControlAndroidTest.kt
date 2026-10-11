package io.github.lrq3000.utterlane

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.history.WavFile
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/** One opt-in functional control, not a benchmark: replay the existing pair's
 * exact windows without capture/storage/archival, then repeat its differing window.
 * A stable result does not prove determinism; variation here cannot be caused by
 * archival. The observed quality envelope is documented in the recording QA report. */
@RunWith(AndroidJUnit4::class)
class RecordingNativeReplayControlAndroidTest {
    @Test fun replayExistingWindowsWithoutArchival(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recordingReplayControl") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        assertEquals("io.github.lrq3000.utterlane", app.packageName)
        val directory = File(app.getExternalFilesDir(null), "recording-qa")
        val prior = JSONObject(File(directory, "control-reference.json").readText())
        val map = prior.getJSONObject("options")
        val supplied = map.keys().asSequence().associateWith { map.getString(it) }
        val options = RuntimeOptions.fromMap(supplied).requireValid()
        assertEquals(supplied, options.toMap())
        app.modelManager.initializeSelection()
        val previous = app.modelManager.selected.value
        app.recognizerManager.selectModel(ModelCatalog.DEFAULT)
        val pcm = File(directory, "control-source.wav")
        val windows = mutableListOf<AudioWindow>()
        val metadata = JSONArray()
        val replies = JSONArray()
        val report = JSONObject().put("options", map).put("model", ModelCatalog.DEFAULT.id)
            .put("timing_measured", false).put("window_metadata", metadata).put("replies", replies)
        val worker = WorkerRecognitionBackend(app, ModelCatalog.DEFAULT)
        fun noArchival() {
            val status = app.archival.state.value
            // Cancelled-job cleanup descriptors may remain durable. They are not
            // active codec work; forceStopAll fences their execution below.
            assertEquals(status.toString(), 0, status.queued + status.running + status.producers)
            assertFalse(app.getSystemService(ActivityManager::class.java).runningAppProcesses
                .any { it.processName == "${app.packageName}:archive" })
        }
        try {
            assertTrue(app.modelManager.ensureVerified())
            report.put("archive_before_stop", app.archival.state.value.toString())
            withContext(Dispatchers.Main) { app.archival.forceStopAll() }
            withTimeout(15000) {
                while (app.archival.state.value.message?.startsWith("Archival stopped.") != true) delay(20)
            }
            report.put("archive_fenced", app.archival.state.value.toString())
            noArchival()
            // Earlier runs saved hashes, not PCM. Reconstruct once with the same
            // Android decoder, require every hash to match, then save exact bytes.
            val segmenter = AudioSegmenter(options = options) { windows += it }
            WavFile(pcm).use { wav ->
                AudioDecoder(app).decode(File(directory, "test-1-speaker-french.m4a").path, {
                    wav.append(it); segmenter.accept(it)
                })
            }
            segmenter.finish()
            assertEquals(prior.getLong("source_samples"), (pcm.length() - 44) / 2)
            assertEquals(3, windows.size)
            val fullDigest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(pcm, "r").use { input ->
                input.seek(44)
                val block = ByteArray(8192)
                while (true) { val n = input.read(block); if (n < 0) break; fullDigest.update(block, 0, n) }
            }
            assertEquals(prior.getJSONObject("off").getString("pcm_sha256"), hex(fullDigest.digest()))
            for ((i, window) in windows.withIndex()) {
                val bytes = ByteBuffer.allocate(window.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asShortBuffer().put(window.samples)
                val hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
                val description = JSONObject().put("start", window.startSample).put("owned_start", window.ownedStart)
                    .put("owned_end", window.ownedEnd).put("final", window.isFinal).put("samples", window.samples.size).put("sha256", hash)
                metadata.put(description)
                for (arm in listOf("off", "on")) {
                    val expected = prior.getJSONObject(arm).getJSONArray("window_pcm").getJSONObject(i)
                    assertEquals(expected.getLong("start"), window.startSample)
                    assertEquals(expected.getLong("owned_end"), window.ownedEnd)
                    assertEquals(expected.getInt("samples"), window.samples.size)
                    assertEquals(expected.getString("sha256"), hash)
                }
                File(directory, "control-window-$i.pcm").writeBytes(bytes.array())
            }
            // Rebuild each old transcript with the exact production ownership
            // crop. No native call, word normalization or tolerance is involved.
            for (arm in listOf("off", "on")) {
                val old = prior.getJSONObject(arm)
                val reconstructed = windows.mapIndexed { i, window ->
                    val result = old.getJSONArray("native_windows").getJSONObject(i)
                    val tokens = result.getJSONArray("tokens")
                    val times = result.getJSONArray("timestamps")
                    WindowText.select(Array(tokens.length()) { tokens.getString(it) },
                        FloatArray(times.length()) { times.getDouble(it).toFloat() }, window)
                }.joinToString(" ")
                assertEquals(old.getString("transcript"), reconstructed)
                report.put("${arm}_crop_reconstructed_exactly", true)
            }
            worker.configure(options)
            withContext(Dispatchers.IO) { worker.prepare() } // includes the normal silence warmup
            val pid = worker.workerPid
            report.put("worker_pid", pid)
            report.put("worker_environment", runCatching {
                JSONArray(File("/proc/$pid/environ").readText().split('\u0000').filter {
                    it.startsWith("CRISPASR_") || it.startsWith("GGML_") || it.startsWith("OMP_") || it.startsWith("OPENBLAS_")
                })
            }.getOrElse { JSONArray().put("unavailable: ${it.javaClass.simpleName}") })
            // The last two requests repeat the disputed first window after the
            // ordinary complete sequence, using the SAME backend and worker PID.
            for (i in listOf(0, 1, 2, 0, 0)) {
                noArchival()
                val result = withContext(Dispatchers.IO) { worker.transcribeWindow(windows[i].samples) }
                assertEquals(pid, worker.workerPid)
                noArchival()
                val text = WindowText.select(result.tokens, result.timestamps, windows[i])
                replies.put(JSONObject().put("window", i).put("owned_text", text)
                    .put("tokens", JSONArray(result.tokens.toList())).put("timestamps", JSONArray(result.timestamps.toList()))
                    .put("ends", JSONArray(result.ends.toList())).put("archive_absent", true))
                if (i == 2) assertTrue(text, text.contains("capture bien ou pas"))
            }
            report.put("complete", true)
        } finally {
            File(directory, "native-replay-control.json").writeText(report.toString(2))
            worker.close()
            app.recognizerManager.selectModel(previous)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
