package io.github.lrq3000.utterlane.asr

import android.content.Context
import android.util.Log
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.RecordingHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** Shared capture/recognition lifecycle for every microphone entry point. */
class MicrophoneSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onText: suspend (String, TranscriptStore) -> Unit,
    private val onComplete: (TranscriptStore?, SessionFailure?) -> Unit,
    private val onCaptureEnded: () -> Unit = {},
    private val onWarning: (String) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val recorder: AudioCapture = AudioRecorder(),
    private val onSessionClosed: () -> Unit = {}
) {
    companion object {
        private val active = AtomicReference<MicrophoneSession?>(null)
        fun isBusy(): Boolean = active.get() != null
        fun resetActive() { active.get()?.let { it.resetRequested = true; it.cancel() } }
    }
    private var job: Job? = null
    val metrics = CaptureMetrics()
    @Volatile private var cancelled = false
    @Volatile private var resetRequested = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        check(job == null)
        if (!active.compareAndSet(null, this)) { onComplete(null, SessionFailure(SessionFailure.Kind.BUSY, context.getString(R.string.stream_busy))); return }
        // Even reset-before-dispatch must enter try/finally so all five callers
        // receive their cleanup callback and release their local session reference.
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var session: TranscriptionSession? = null
            var recording: RecordingHistory.Recording? = null
            var lease: Closeable? = null
            var power: TranscriptionPower? = null
            var failure: SessionFailure? = null
            var phase = SessionFailure.Kind.MODEL
            var finalizationWarning: String? = null
            var activityObserver: Job? = null
            var diagnosticObserver: Job? = null
            var captureDiagnostics: io.github.lrq3000.utterlane.diagnostics.CaptureDiagnosticSession? = null
            val captureFailure = java.util.concurrent.atomic.AtomicReference<SessionFailure?>(null)
            val historyWriteFailed = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                power = TranscriptionPower(context) { recorder.resumeAfterSleep() }
                val app = UtterlaneApp.instance
                activityObserver = launch {
                    app.recognizerManager.activity.collect {
                        // Ignore a previous operation's resting status at subscription time.
                        if (it.active || metrics.state.value.recognition.active) metrics.recognition(it)
                    }
                }
                // Capture, queue and every wake reopen retain one immutable snapshot.
                // Preferences changed during recording take effect only next session.
                val captureOptions = app.settingsRepository.runtimeOptions.first()
                captureDiagnostics = app.recognitionDiagnostics.capture(captureOptions)
                diagnosticObserver = launch { metrics.state.collect { captureDiagnostics.record(it) } }
                metrics.model(app.modelManager.selected.value.name)
                session = app.recognizerManager.createSession(captureOptions, onProcessed = { end, ms -> metrics.processed(end, ms) }) { delta ->
                    if (!cancelled) withContext(Dispatchers.Main) { onText(delta, session!!.store) }
                }
                phase = SessionFailure.Kind.INFERENCE
                metrics.model(app.modelManager.selected.value.name)
                val retention = app.settingsRepository.historyRetention.first()
                try {
                    recording = app.recordingHistory.begin(retention)
                    recording?.let { lease = app.recordingHistory.acquire(it.entry.id) }
                } catch (e: Exception) {
                    val warning = context.getString(R.string.history_save_failed, e.message ?: "Storage error")
                    Log.e("MicrophoneSession", warning, e)
                    withContext(Dispatchers.Main) { onWarning(warning) }
                }
                val queue = BoundedAudioQueue(captureOptions)
                coroutineScope {
                    val ticker = launch { while (isActive) { metrics.tick(); delay(200) } }
                    val capture = launch(Dispatchers.IO) {
                        try {
                            val captureContext = currentCoroutineContext()
                            withContext(Dispatchers.Main) { if (!cancelled) onReady() }
                            recorder.setObserver(object : CaptureObserver {
                                override fun onStarted() { metrics.started() }
                                override fun onSilenced(silenced: Boolean) { metrics.silenced(silenced) }
                            })
                            recorder.startRecording(captureOptions, { samples ->
                                val accepted = queue.offer(samples)
                                metrics.samples(samples, accepted)
                                if (!accepted) {
                                    captureFailure.compareAndSet(null, SessionFailure(SessionFailure.Kind.CAPACITY, context.getString(R.string.stream_overload)))
                                    recorder.stop()
                                }
                            }, { captureContext.isActive })
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            Log.e("MicrophoneSession", "Capture failed", e)
                            captureFailure.compareAndSet(null, SessionFailure(SessionFailure.Kind.AUDIO, e.message ?: context.getString(R.string.toast_recording_error)))
                        } finally {
                            queue.close()
                            metrics.captureEnded()
                            withContext(NonCancellable + Dispatchers.Main) { if (!cancelled) onCaptureEnded() }
                        }
                    }
                    val saved = recording
                    if (saved == null) {
                        for (samples in queue.blocks) session.accept(samples)
                    } else {
                        data class Published(val length: Long, val done: Boolean = false, val failed: Boolean = false)
                        val published = MutableStateFlow(Published(0))
                        val recovery = Channel<ShortArray>(Channel.RENDEZVOUS)
                        val writer = launch(Dispatchers.IO) {
                            var writeFailed = false
                            try {
                                for (samples in queue.blocks) {
                                    if (writeFailed) { recovery.send(samples); continue }
                                    val before = saved.writtenSamples
                                    try {
                                        saved.append(samples)
                                        published.value = Published(saved.writtenSamples)
                                    } catch (e: Exception) {
                                        writeFailed = true
                                        historyWriteFailed.set(true)
                                        val warning = context.getString(R.string.history_save_failed, e.message ?: "Storage error")
                                        Log.e("MicrophoneSession", warning, e)
                                        published.value = Published(saved.writtenSamples, failed = true)
                                        withContext(Dispatchers.Main) { if (!cancelled) onWarning(warning) }
                                        // History is optional. Continue through the same bounded
                                        // live queue; only genuine backlog overflow stops capture.
                                        // Any prefix already published belongs to the disk reader.
                                        val written = (saved.writtenSamples - before).toInt()
                                        if (written < samples.size) recovery.send(samples.copyOfRange(written, samples.size))
                                    }
                                }
                            } finally {
                                published.value = Published(saved.writtenSamples, done = true, failed = writeFailed)
                                recovery.close()
                            }
                        }
                        var offset = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val snapshot = published.value
                            if (offset < snapshot.length) {
                                val samples = app.recordingHistory.read(saved.entry.id, offset, minOf(3200L, snapshot.length - offset).toInt())
                                session.accept(samples)
                                offset += samples.size
                            } else if (snapshot.failed || snapshot.done) break
                            else published.first { it.length > offset || it.done || it.failed }
                        }
                        // If writing failed, the bounded recovery channel lets accepted
                        // blocks finish recognition without making another audio file.
                        for (samples in recovery) session.accept(samples)
                        writer.join()
                    }
                    capture.join()
                    ticker.cancel()
                }
                failure = captureFailure.get()
                session.finish()
                if (session.store.segments == 0 && failure == null) failure = SessionFailure(SessionFailure.Kind.NO_SPEECH, context.getString(R.string.toast_no_speech))
            } catch (e: CancellationException) { cancelled = true; throw e
            } catch (e: Exception) {
                Log.e("MicrophoneSession", "Transcription failed", e)
                failure = SessionFailure(phase, e.message ?: context.getString(R.string.transcribe_error_failed))
            } finally {
                activityObserver?.cancel()
                diagnosticObserver?.cancel()
                try {
                    recorder.stop()
                    withContext(NonCancellable + Dispatchers.IO) {
                        session?.close()
                        try { recording?.finish((failure != null && failure.kind != SessionFailure.Kind.NO_SPEECH) || cancelled || historyWriteFailed.get()) }
                        catch (e: Exception) { Log.e("MicrophoneSession", "History finalization failed", e); finalizationWarning = context.getString(R.string.history_save_failed, e.message ?: "Storage error") }
                        finally {
                            try { lease?.close() }
                            finally { active.compareAndSet(this@MicrophoneSession, null) }
                        }
                        try { UtterlaneApp.instance.recordingHistory.prune(UtterlaneApp.instance.settingsRepository.historyRetention.first()) }
                        catch (e: Exception) { Log.e("MicrophoneSession", "History pruning failed", e) }
                    }
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (cancelled) metrics.cancelled() else metrics.completed(failure?.message)
                        captureDiagnostics?.record(metrics.state.value)
                        if (!cancelled) finalizationWarning?.let { onWarning(it) }
                        if (resetRequested) onComplete(session?.store, SessionFailure(SessionFailure.Kind.MODEL, context.getString(R.string.model_reset_done)))
                        else if (!cancelled) onComplete(session?.store, failure)
                        else session?.store?.let { io.github.lrq3000.utterlane.service.TranscriptRecovery.show(context, it) }
                    }
                } finally {
                    try { withContext(NonCancellable + Dispatchers.Main) { onSessionClosed() } }
                    finally { power?.close() }
                }
            }
        }
        // Covers a scope cancelled before the launch body executes.
        job!!.invokeOnCompletion { active.compareAndSet(this, null) }
    }

    fun stop() { metrics.stopping(); recorder.stop() }
    fun cancel() { cancelled = true; metrics.cancelled(); recorder.stop(); job?.cancel() }
}
