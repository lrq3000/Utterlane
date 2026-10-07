package io.github.lrq3000.utterlane.asr

import android.content.Context
import android.util.Log
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.MicrophoneRecordings
import io.github.lrq3000.utterlane.history.HistoryRetention
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
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
            var recording: MicrophoneRecordings.Recording? = null
            var power: TranscriptionPower? = null
            var failure: SessionFailure? = null
            var finalizationWarning: String? = null
            var activityObserver: Job? = null
            var diagnosticObserver: Job? = null
            var captureDiagnostics: io.github.lrq3000.utterlane.diagnostics.CaptureDiagnosticSession? = null
            var ticker: Job? = null
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
                metrics.preparingModel()
                val retention = app.settingsRepository.historyRetention.first()
                try {
                    recording = app.microphoneRecordings.begin(retention)
                } catch (e: Exception) {
                    val warning = context.getString(R.string.history_save_failed, e.message ?: "Storage error")
                    Log.e("MicrophoneSession", warning, e)
                    withContext(Dispatchers.Main) { onWarning(warning) }
                    // Optional retained history must not gate dictation. A private
                    // temporary destination still preserves the input for recovery.
                    recording = app.microphoneRecordings.begin(HistoryRetention.NONE)
                }
                ticker = launch { while (isActive) { metrics.tick(); delay(200) } }
                recorder.setObserver(object : CaptureObserver {
                    override fun onStarted() {
                        metrics.started()
                        // Ready means AudioRecord really started, not that a model
                        // or an optimistic UI transition happened to complete.
                        launch(Dispatchers.Main) { if (!cancelled) onReady() }
                    }
                    override fun onSilenced(silenced: Boolean) { metrics.silenced(silenced) }
                })
                val outcome = MicrophonePipeline(recorder, checkNotNull(recording), captureOptions,
                    prepare = {
                        val prepared = app.recognizerManager.createSession(captureOptions, onProcessed = metrics::processed) { delta ->
                            if (!cancelled) withContext(Dispatchers.Main) { onText(delta, session!!.store) }
                        }
                        session = prepared
                        object : MicrophonePipeline.Consumer {
                            override suspend fun accept(samples: ShortArray) = prepared.accept(samples)
                            override suspend fun finish() = prepared.finish()
                            override fun close() = prepared.close()
                        }
                    }, onSamples = { metrics.samples(it, true) },
                    onCaptureEnded = {
                        metrics.captureEnded()
                        withContext(Dispatchers.Main) { if (!cancelled) onCaptureEnded() }
                    }, onRecognitionReady = {
                        metrics.model(app.modelManager.selected.value.name)
                        metrics.modelPrepared()
                    }, onRecognitionFailure = {
                        Log.e("MicrophoneSession", "Recognition failed; capture continues", it.cause)
                        metrics.recognitionFailed(it.cause.message ?: context.getString(R.string.transcribe_error_failed))
                    }).run()
                failure = outcome.failure?.let(::sessionFailure)
                outcome.failure?.takeIf { it.stage in setOf(MicrophonePipeline.Stage.AUDIO, MicrophonePipeline.Stage.STORAGE, MicrophonePipeline.Stage.CAPACITY) }
                    ?.let { Log.e("MicrophoneSession", "Capture/storage failed", it.cause) }
                if (session?.store?.segments == 0 && failure == null) failure = SessionFailure(SessionFailure.Kind.NO_SPEECH, context.getString(R.string.toast_no_speech))
            } catch (e: CancellationException) { cancelled = true; throw e
            } catch (e: Exception) {
                Log.e("MicrophoneSession", "Transcription failed", e)
                failure = SessionFailure(SessionFailure.Kind.AUDIO, e.message ?: context.getString(R.string.toast_recording_error))
            } finally {
                activityObserver?.cancel()
                diagnosticObserver?.cancel()
                ticker?.cancel()
                try {
                    recorder.stop()
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { session?.close() }
                        catch (e: Exception) { Log.e("MicrophoneSession", "Recognition close failed", e) }
                        try { recording?.finish((failure != null && failure.kind != SessionFailure.Kind.NO_SPEECH) || cancelled) }
                        catch (e: Exception) { Log.e("MicrophoneSession", "History finalization failed", e); finalizationWarning = context.getString(R.string.history_save_failed, e.message ?: "Storage error") }
                        finally {
                            try {
                                val failed = failure
                                val saved = recording
                                if (!cancelled && failed != null && failed.kind != SessionFailure.Kind.NO_SPEECH && saved != null) {
                                    val recoveryId = UtterlaneApp.instance.microphoneRecordings.recover(saved,
                                        failed.message, failed.kind == SessionFailure.Kind.MODEL)
                                    failure = failed.copy(recoveryId = recoveryId)
                                } else saved?.close()
                            }
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

    private fun sessionFailure(failure: MicrophonePipeline.Failure): SessionFailure {
        val kind = when (failure.stage) {
            MicrophonePipeline.Stage.PREPARATION -> SessionFailure.Kind.MODEL
            MicrophonePipeline.Stage.INFERENCE -> SessionFailure.Kind.INFERENCE
            MicrophonePipeline.Stage.CAPACITY -> SessionFailure.Kind.CAPACITY
            MicrophonePipeline.Stage.AUDIO, MicrophonePipeline.Stage.STORAGE -> SessionFailure.Kind.AUDIO
        }
        val message = when (failure.stage) {
            MicrophonePipeline.Stage.STORAGE -> context.getString(R.string.recording_storage_failed, failure.cause.message.orEmpty())
            MicrophonePipeline.Stage.CAPACITY -> context.getString(R.string.recording_storage_overload)
            else -> failure.cause.message ?: context.getString(R.string.transcribe_error_failed)
        }
        return SessionFailure(kind, message)
    }
}
