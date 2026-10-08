package io.github.lrq3000.utterlane.asr

import android.content.Context
import android.util.Log
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.RecordingHistory
import kotlinx.coroutines.*
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
        fun resetActive() { active.get()?.let { it.resetRequested = true; it.cancel(discard = false) } }
    }
    private var job: Job? = null
    val metrics = CaptureMetrics()
    @Volatile private var cancelled = false
    @Volatile private var resetRequested = false
    @Volatile private var discardRequested = false
    @Volatile private var recordingId: String? = null

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
            var ticker: Job? = null
            var visualOptionsObserver: Job? = null
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
                metrics.setVisualRefreshRate(app.settingsRepository.visualRefreshRate.first())
                visualOptionsObserver = launch { app.settingsRepository.visualRefreshRate.collect { metrics.setVisualRefreshRate(it) } }
                captureDiagnostics = app.recognitionDiagnostics.capture(captureOptions)
                diagnosticObserver = launch { metrics.state.collect { captureDiagnostics.record(it) } }
                metrics.model(app.modelManager.selected.value.name)
                metrics.preparing(true)
                val retention = app.settingsRepository.audioHistoryRetention.first()
                val automaticHistory = app.settingsRepository.audioHistoryEnabled.first()
                val saveTranscripts = app.settingsRepository.transcriptHistoryEnabled.first()
                val textRetention = app.settingsRepository.transcriptHistoryRetention.first()
                phase = SessionFailure.Kind.AUDIO
                // This minimal private-storage setup precedes capture; model
                // verification/loading/warm-up do not. The file is also the
                // processing backlog when completed-recording history is off.
                val saved = app.recordingHistory.begin(retention, automaticHistory)
                recording = saved
                recordingId = saved.entry.id
                lease = app.recordingHistory.acquire(saved.entry.id)
                recorder.setObserver(object : CaptureObserver {
                    override fun onStarted() {
                        metrics.started()
                        launch(Dispatchers.Main) { if (!cancelled) onReady() }
                    }
                    override fun onSilenced(silenced: Boolean) { metrics.silenced(silenced) }
                })
                ticker = launch { while (isActive) { metrics.tick(); delay(metrics.visualRefreshIntervalMillis()) } }
                val result = RecordingPipeline(recorder, app.recordingHistory, saved, captureOptions).run(
                    prepare = {
                        phase = SessionFailure.Kind.MODEL
                        session = app.recognizerManager.createSession(captureOptions, onProcessed = metrics::processed) { delta ->
                            if (!cancelled) withContext(Dispatchers.Main) { onText(delta, session!!.store) }
                        }
                        session!!.store.attachSource(TranscriptSource(saved.entry.id,
                            modelName = app.modelManager.selected.value.name, modelId = app.modelManager.selected.value.id))
                        phase = SessionFailure.Kind.INFERENCE
                        metrics.preparing(false)
                        metrics.model(app.modelManager.selected.value.name)
                    },
                    accept = { checkNotNull(session).accept(it) },
                    finish = {
                        val complete = checkNotNull(session)
                        complete.finish()
                        if (saveTranscripts && textRetention != io.github.lrq3000.utterlane.history.HistoryRetention.NONE && complete.store.segments > 0) {
                            val text = app.transcriptHistory.save(complete.store.file, metrics.state.value.modelName, saved.entry.id,
                                modelId = app.modelManager.selected.value.id)
                            complete.store.attachSource(complete.store.source.copy(transcriptId = text.id))
                        }
                    },
                    onSamples = { metrics.samples(it, true) },
                    onCaptureEnded = {
                        metrics.captureEnded()
                        withContext(Dispatchers.Main) { if (!cancelled) onCaptureEnded() }
                    },
                    onProcessingFailed = { error ->
                        Log.e("MicrophoneSession", "Recognition failed; continuing audio capture", error)
                        metrics.recognitionFailed(error.message ?: context.getString(R.string.transcribe_error_failed))
                    }, closeConsumer = { session?.close() })
                failure = when {
                    result.storageError != null -> SessionFailure(SessionFailure.Kind.AUDIO,
                        context.getString(R.string.history_save_failed, result.storageError.message ?: "Storage error"))
                    result.captureError is RecordingPipeline.CaptureCapacityException ->
                        SessionFailure(SessionFailure.Kind.CAPACITY, context.getString(R.string.stream_overload))
                    result.captureError != null -> SessionFailure(SessionFailure.Kind.AUDIO,
                        result.captureError.message ?: context.getString(R.string.toast_recording_error))
                    result.processingError != null -> SessionFailure(phase,
                        result.processingError.message ?: context.getString(R.string.transcribe_error_failed))
                    else -> null
                }
                if (session?.store?.segments == 0 && failure == null) failure = SessionFailure(SessionFailure.Kind.NO_SPEECH, context.getString(R.string.toast_no_speech))
            } catch (e: CancellationException) { cancelled = true; throw e
            } catch (e: Exception) {
                Log.e("MicrophoneSession", "Transcription failed", e)
                failure = SessionFailure(phase, e.message ?: context.getString(R.string.transcribe_error_failed))
            } finally {
                ticker?.cancel()
                visualOptionsObserver?.cancel()
                activityObserver?.cancel()
                diagnosticObserver?.cancel()
                try {
                    recorder.stop()
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { session?.close() } catch (e: Exception) { Log.e("MicrophoneSession", "Recognition cleanup failed", e) }
                        try { if (discardRequested) recording?.let { UtterlaneApp.instance.recordingHistory.dismiss(it.entry.id) } }
                        catch (e: Exception) { Log.e("MicrophoneSession", "Discard persistence failed; still finalizing audio", e) }
                        try { recording?.finish((failure != null && failure.kind != SessionFailure.Kind.NO_SPEECH) || cancelled) }
                        catch (e: Exception) { Log.e("MicrophoneSession", "History finalization failed", e); finalizationWarning = context.getString(R.string.history_save_failed, e.message ?: "Storage error") }
                        finally {
                            try {
                                val failed = failure
                                if (!discardRequested && failed != null && failed.kind != SessionFailure.Kind.NO_SPEECH) {
                                    recording?.takeIf { it.writtenSamples > 0 }?.let {
                                        UtterlaneApp.instance.recordingHistory.recordFailure(it.entry.id, failed.message, failed.kind.name)
                                    }
                                }
                            } catch (e: Exception) { Log.e("MicrophoneSession", "Could not save recovery details", e) }
                            finally {
                                try { lease?.close() }
                                finally { active.compareAndSet(this@MicrophoneSession, null) }
                            }
                        }
                    }
                    withContext(NonCancellable + Dispatchers.Main) {
                        val originalFailure = failure
                        recording?.takeIf { !discardRequested && it.writtenSamples > 0 && (cancelled || (originalFailure != null && originalFailure.kind != SessionFailure.Kind.NO_SPEECH)) }?.let {
                            io.github.lrq3000.utterlane.history.RecordingRecovery.show(context, it.entry.id)
                            failure = originalFailure?.copy(message = originalFailure.message + "\n" + context.getString(R.string.recording_recovery_saved), recoveryId = it.entry.id)
                        }
                        if (cancelled) metrics.cancelled() else metrics.completed(failure?.message)
                        captureDiagnostics?.record(metrics.state.value)
                        if (!cancelled) finalizationWarning?.let { onWarning(it) }
                        if (resetRequested) onComplete(session?.store, SessionFailure(SessionFailure.Kind.MODEL, context.getString(R.string.model_reset_done)))
                        else if (!cancelled) onComplete(session?.store, failure)
                        else session?.store?.let {
                            if (discardRequested) withContext(Dispatchers.IO) { it.dispose() }
                            else io.github.lrq3000.utterlane.service.TranscriptRecovery.show(context, it)
                        }
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
    fun cancel(discard: Boolean = true) {
        discardRequested = discardRequested || discard
        cancelled = true; metrics.cancelled(); recorder.stop()
        // Persist explicit intent before cancelling processing. The finalizer
        // also checks this flag if its owner disappears during this handoff.
        if (discard && recordingId != null && job?.isActive == true) {
            UtterlaneApp.instance.applicationScope.launch(Dispatchers.IO) {
                try { recordingId?.let { UtterlaneApp.instance.recordingHistory.dismiss(it) } }
                catch (e: Exception) { Log.e("MicrophoneSession", "Could not persist discard intent", e) }
                finally { job?.cancel() }
            }
        } else job?.cancel()
    }
}
