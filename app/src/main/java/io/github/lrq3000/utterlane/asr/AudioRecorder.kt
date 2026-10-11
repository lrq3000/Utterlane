package io.github.lrq3000.utterlane.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import android.os.PowerManager
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.util.concurrent.atomic.AtomicBoolean
import io.github.lrq3000.utterlane.audio.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class AudioRecorder : AudioCapture {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var readLoop: CaptureReadLoop? = null
    private val power by lazy { UtterlaneApp.instance.getSystemService(PowerManager::class.java) }
    private var observer: CaptureObserver? = null
    private var platformCallback: android.media.AudioManager.AudioRecordingCallback? = null
    private var route: AndroidCaptureRoute? = null
    private val silencing = CaptureSilencing { observer?.onSilenced(it) }
    private var continueCapture: () -> Boolean = { false }
    private var configuredMicrophone: MicrophoneOptions? = null
    private var microphoneOptions = MicrophoneOptions.STANDARD
    private var effects: MicrophoneEffects? = null
    private var diagnostic: MicrophoneDiagnostics.Session? = null
    private var effectInputId: Int? = null
    private var captureFormat = io.github.lrq3000.utterlane.archive.PcmFormat()
    private var preparedConfiguration: CaptureConfiguration? = null
    private val channelConfig get() = if (captureFormat.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    /** Test real AudioRecord initialization, retaining that same recorder for the
     * actual session. A rejected tuple falls back before any source clock starts. */
    @SuppressLint("MissingPermission")
    override fun configureFormat(requested: io.github.lrq3000.utterlane.archive.PcmFormat): io.github.lrq3000.utterlane.archive.PcmFormat =
        configureFormat(requested, RuntimeOptions())
    override fun configureFormat(requested: io.github.lrq3000.utterlane.archive.PcmFormat, options: RuntimeOptions): io.github.lrq3000.utterlane.archive.PcmFormat {
        check(!isRecording.get() && readLoop == null)
        closeRecorder()
        snapshotMicrophone()
        captureFormat = requested
        if (requested.native) {
            preparedConfiguration = configuration(options)
            return captureFormat
        }
        try {
            val minimum = AudioRecord.getMinBufferSize(requested.sampleRate, channelConfig, AUDIO_FORMAT)
            check(minimum > 0)
            val selected = CaptureConfiguration(requested, options, minimum)
            val record = createRecorder(selected)
            if (record.state != AudioRecord.STATE_INITIALIZED || record.sampleRate != requested.sampleRate || record.channelCount != requested.channels) {
                record.release(); error("Requested microphone format is unsupported")
            }
            audioRecord = record
            preparedConfiguration = selected
        } catch (e: SecurityException) { throw e
        } catch (_: IllegalArgumentException) { captureFormat = io.github.lrq3000.utterlane.archive.PcmFormat()
        } catch (_: IllegalStateException) { captureFormat = io.github.lrq3000.utterlane.archive.PcmFormat() }
        if (captureFormat != requested) preparedConfiguration = configuration(options)
        return captureFormat
    }
    private fun configuration(options: RuntimeOptions) = CaptureConfiguration(captureFormat, options,
        AudioRecord.getMinBufferSize(captureFormat.sampleRate, channelConfig, AUDIO_FORMAT))
    @SuppressLint("MissingPermission")
    private fun createRecorder(configuration: CaptureConfiguration) = configuration.create { format, bytes ->
        AudioRecord(microphoneOptions.source.androidSource, format.sampleRate,
            if (format.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT, bytes)
    }
    override fun setObserver(observer: CaptureObserver) { this.observer = observer }
    override fun configureMicrophone(options: MicrophoneOptions) {
        check(!isRecording.get() && readLoop == null) { "Microphone configuration is frozen during capture" }
        // A format probe may already own an unstarted recorder. Changing source
        // invalidates that recorder, never relabels it as the new configuration.
        closeRecorder()
        preparedConfiguration = null
        configuredMicrophone = options
    }

    private fun snapshotMicrophone() {
        microphoneOptions = configuredMicrophone ?: runBlocking {
            UtterlaneApp.instance.settingsRepository.microphoneSettings.first().options
        }.also { configuredMicrophone = it }
    }

    override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) =
        startRecording(RuntimeOptions(), onSamples, shouldContinue)

    override fun startRecording(options: RuntimeOptions, onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
        val selected = preparedConfiguration ?: configuration(options).also { preparedConfiguration = it }
        val buffers = selected.buffers
        if (stopRequested.get()) return
        if (!isRecording.compareAndSet(false, true)) return
        continueCapture = { isRecording.get() && !stopRequested.get() && shouldContinue() }
        var captureFailure: String? = null

        try {
            val loop = CaptureReadLoop(options = selected.options, blockSamples = buffers.blockSamples)
            readLoop = loop
            val app = UtterlaneApp.instance
            snapshotMicrophone()
            diagnostic = app.microphoneDiagnostics.begin(microphoneOptions)
            val selection = try { runBlocking { app.audioInputs.snapshotForRecording() } }
            catch (e: Exception) {
                Log.w(TAG, "Input preferences unavailable; starting with phone microphone", e)
                AudioInputState(listOf(AudioInput(AudioInput.PHONE_KEY, "", false)), InputPreferences())
            }
            route = AndroidCaptureRoute(app, app.audioInputs, selection, continueCapture, onState = { state ->
                observer?.onInputChanged(state)
                diagnostic?.input(state)
                if (effectInputId != state.actual?.inputId) {
                    effectInputId = state.actual?.inputId
                    effects?.refresh()?.let { diagnostic?.effects(it) }
                }
            }, options = microphoneOptions, onDiagnostic = { diagnostic?.route(it) })
            if (!openRecorder(selected)) return
            observer?.onStarted()
            // Nonblocking is supported since API 23 (minSdk is 26). Only this
            // capture worker opens/releases the recorder, including wake recovery.
            loop.run(
                read = { block ->
                    val record = checkNotNull(audioRecord)
                    val silenced = silencing.poll()
                    route?.beforeRead(record, silenced)
                    if (!continueCapture()) 0 else {
                        val count = record.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
                        route?.afterRead(record, count, silenced)
                        count
                    }
                },
                reopen = { Log.i(TAG, "Reopening microphone after capture interruption"); closeRecorder(); openRecorder(selected) },
                onSamples = onSamples,
                shouldContinue = continueCapture,
                canRecover = { route?.isFallback == true || (power.isInteractive && !power.isDeviceIdleMode) },
                routeRecoveryRequested = { route?.takeReopenRequest() == true }
            )
        } catch (e: IllegalArgumentException) {
            captureFailure = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Failed to create AudioRecord", e)
            throw e
        } catch (e: IllegalStateException) {
            captureFailure = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "AudioRecord illegal state", e)
            throw e
        } catch (e: SecurityException) {
            captureFailure = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "AudioRecord permission denied", e)
            throw e
        } catch (e: RuntimeException) {
            captureFailure = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Audio capture failed", e)
            throw e
        } finally {
            isRecording.set(false)
            readLoop = null
            try { closeRecorder() } finally {
                try { route?.close() } finally { route = null; diagnostic?.finish(captureFailure); diagnostic = null }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(configuration: CaptureConfiguration): Boolean {
        if (!continueCapture()) return false
        if (audioRecord == null) audioRecord = createRecorder(configuration)

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            error("AudioRecord failed to initialize")
        }

        val record = checkNotNull(audioRecord)
        diagnostic?.format(record.audioSource, record.sampleRate, record.channelCount, record.audioSessionId,
            record.audioFormat, capturing = false)
        MicrophoneCaptureConfiguration.problem(UtterlaneApp.instance, record, microphoneOptions, configuration.format)?.let { error(it) }
        effects = MicrophoneEffects(record.audioSessionId, microphoneOptions.preprocessing)
        diagnostic?.effects(checkNotNull(effects).refresh())
        val invalidateConfiguration = silencing.opened {
            if (android.os.Build.VERSION.SDK_INT >= 29) record.activeRecordingConfiguration?.isClientSilenced else false
        }
        if (!continueCapture()) return false
        route?.attach(record)

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val callback = object : android.media.AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
                    // Only the capture worker queries/publishes configuration.
                    // This signal remains bound to this recorder after release.
                    invalidateConfiguration()
                }
            }
            platformCallback = callback
            audioRecord?.registerAudioRecordingCallback(java.util.concurrent.Executor { it.run() }, callback)
        }
        if (!continueCapture()) return false
        record.startRecording()
        diagnostic?.format(record.audioSource, record.sampleRate, record.channelCount, record.audioSessionId, record.audioFormat)
        diagnostic?.effects(checkNotNull(effects).refresh())
        route?.started()
        silencing.poll()
        return true
    }

    private fun closeRecorder() {
        // Only the capture worker owns release. A concurrent stop never frees
        // AudioRecord while a read is using native resources.
        val record = audioRecord ?: return
        audioRecord = null
        try {
            route?.detach(record)
            try { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            catch (e: IllegalStateException) { Log.e(TAG, "Error stopping AudioRecord", e) }
            if (android.os.Build.VERSION.SDK_INT >= 29) platformCallback?.let { record.unregisterAudioRecordingCallback(it) }
        } finally {
            platformCallback = null
            try { effects?.close() } finally { effects = null; effectInputId = null; record.release() }
        }
    }

    override fun resumeAfterSleep() { readLoop?.resumeAfterSleep() }
    override fun releaseAfterCapture() {
        check(readLoop == null) { "Capture must exit before releasing its prepared recorder" }
        closeRecorder()
    }

    override fun stop() {
        stopRequested.set(true)
        isRecording.set(false)
    }

    fun release() = stop()

    fun isRecording(): Boolean = isRecording.get()
}
