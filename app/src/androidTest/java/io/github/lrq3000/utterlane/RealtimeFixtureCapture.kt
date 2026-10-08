package io.github.lrq3000.utterlane

import android.os.SystemClock
import io.github.lrq3000.utterlane.asr.AudioCapture
import org.json.JSONArray
import org.json.JSONObject

/** Fixed, <=30-second PCM input delivered on absolute real-time deadlines.
 * RecordingPipeline gives this producer its own thread and disk-backed backlog;
 * slow recognition must never slow the simulated microphone's clock.
 */
internal class RealtimeFixtureCapture(
    private val pcm: ShortArray,
    private val blockSamples: Int,
    private val processed: () -> Long
) : AudioCapture {
    init { require(pcm.isNotEmpty() && pcm.size <= 30 * 16000 && blockSamples in 1..3200) }
    @Volatile private var stopped = false
    val sampleCount: Int get() = pcm.size
    var startedAt = 0L
        private set
    var stoppedAt = 0L
        private set
    var capturedSamples = 0
        private set
    private var processedAtStop = 0L
    private var maxBacklog = 0L
    private var maxLatenessNs = 0L
    private val trace = JSONArray()

    override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        startedAt = startNs / 1_000_000
        var nextTrace = 16000
        try {
            while (capturedSamples < pcm.size && !stopped && shouldContinue()) {
                val end = minOf(capturedSamples + blockSamples, pcm.size)
                val due = startNs + end * 1_000_000_000L / 16000
                while (!stopped && shouldContinue()) {
                    val remaining = due - SystemClock.elapsedRealtimeNanos()
                    if (remaining <= 0) break
                    Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                }
                if (stopped || !shouldContinue()) break
                maxLatenessNs = maxOf(maxLatenessNs, SystemClock.elapsedRealtimeNanos() - due)
                onSamples(pcm.copyOfRange(capturedSamples, end))
                capturedSamples = end
                val completed = processed()
                maxBacklog = maxOf(maxBacklog, (capturedSamples - completed).coerceAtLeast(0))
                if (capturedSamples >= nextTrace) {
                    trace.put(observation(completed))
                    nextTrace += 16000
                }
            }
        } finally {
            stoppedAt = SystemClock.elapsedRealtime()
            processedAtStop = processed()
            trace.put(observation(processedAtStop))
        }
    }

    private fun observation(completed: Long) = JSONObject().apply {
        put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
        put("captured_audio_ms", capturedSamples / 16.0)
        put("processed_audio_ms", completed / 16.0)
        put("backlog_audio_ms", (capturedSamples - completed).coerceAtLeast(0) / 16.0)
    }

    fun report(finishedAt: Long) = JSONObject().apply {
        put("capture_elapsed_ms", stoppedAt - startedAt)
        put("processed_at_stop_audio_ms", processedAtStop / 16.0)
        put("backlog_at_stop_audio_ms", (capturedSamples - processedAtStop).coerceAtLeast(0) / 16.0)
        put("catchup_after_stop_ms", (finishedAt - stoppedAt).coerceAtLeast(0))
        put("max_backlog_audio_ms", maxBacklog / 16.0)
        put("max_capture_lateness_ms", maxLatenessNs / 1_000_000.0)
        put("backlog_trace", trace)
    }

    override fun stop() { stopped = true }
}
