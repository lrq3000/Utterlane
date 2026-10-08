package io.github.lrq3000.utterlane.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.PowerManager
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.util.concurrent.atomic.AtomicBoolean
import io.github.lrq3000.utterlane.audio.*
import kotlinx.coroutines.runBlocking

class AudioRecorder : AudioCapture {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
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
    override fun setObserver(observer: CaptureObserver) { this.observer = observer }

    override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) =
        startRecording(RuntimeOptions(), onSamples, shouldContinue)

    override fun startRecording(options: RuntimeOptions, onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
        val buffers = CaptureBufferPolicy(options)
        if (stopRequested.get()) return
        if (!isRecording.compareAndSet(false, true)) return
        continueCapture = { isRecording.get() && !stopRequested.get() && shouldContinue() }

        try {
            val loop = CaptureReadLoop(options = options)
            readLoop = loop
            val bufferSize = buffers.recorderBufferBytes(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT))
            val app = UtterlaneApp.instance
            val selection = try { runBlocking { app.audioInputs.snapshotForRecording() } }
            catch (e: Exception) {
                Log.w(TAG, "Input preferences unavailable; starting with phone microphone", e)
                AudioInputState(listOf(AudioInput(AudioInput.PHONE_KEY, "", false)), InputPreferences())
            }
            route = AndroidCaptureRoute(app, app.audioInputs, selection, continueCapture) { observer?.onInputChanged(it) }
            if (!openRecorder(bufferSize)) return
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
                reopen = { Log.i(TAG, "Reopening microphone after capture interruption"); closeRecorder(); openRecorder(bufferSize) },
                onSamples = onSamples,
                shouldContinue = continueCapture,
                canRecover = { route?.isFallback == true || (power.isInteractive && !power.isDeviceIdleMode) },
                routeRecoveryRequested = { route?.takeReopenRequest() == true }
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            throw e
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord illegal state", e)
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord permission denied", e)
            throw e
        } finally {
            isRecording.set(false)
            readLoop = null
            try { closeRecorder() } finally { route?.close(); route = null }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(bufferSize: Int): Boolean {
        if (!continueCapture()) return false
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            error("AudioRecord failed to initialize")
        }

        val record = checkNotNull(audioRecord)
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
            try { record.stop() } catch (e: IllegalStateException) { Log.e(TAG, "Error stopping AudioRecord", e) }
            if (android.os.Build.VERSION.SDK_INT >= 29) platformCallback?.let { record.unregisterAudioRecordingCallback(it) }
        } finally { platformCallback = null; record.release() }
    }

    override fun resumeAfterSleep() { readLoop?.resumeAfterSleep() }

    override fun stop() {
        stopRequested.set(true)
        isRecording.set(false)
    }

    fun release() = stop()

    fun isRecording(): Boolean = isRecording.get()
}
