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

class AudioRecorder : AudioCapture {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var readLoop: CaptureReadLoop? = null
    private val power by lazy { UtterlaneApp.instance.getSystemService(PowerManager::class.java) }
    private var observer: CaptureObserver? = null
    private var platformCallback: android.media.AudioManager.AudioRecordingCallback? = null
    override fun setObserver(observer: CaptureObserver) { this.observer = observer }

    override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) =
        startRecording(RuntimeOptions(), onSamples, shouldContinue)

    override fun startRecording(options: RuntimeOptions, onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
        val buffers = CaptureBufferPolicy(options)
        if (stopRequested.get()) return
        if (!isRecording.compareAndSet(false, true)) return

        try {
            val loop = CaptureReadLoop(options = options)
            readLoop = loop
            val bufferSize = buffers.recorderBufferBytes(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT))
            openRecorder(bufferSize)
            observer?.onStarted()
            // Nonblocking is supported since API 23 (minSdk is 26). Only this
            // capture worker opens/releases the recorder, including wake recovery.
            loop.run(
                read = { audioRecord!!.read(it, 0, it.size, AudioRecord.READ_NON_BLOCKING) },
                reopen = { Log.i(TAG, "Reopening microphone after capture interruption"); closeRecorder(); openRecorder(bufferSize) },
                onSamples = onSamples,
                shouldContinue = { isRecording.get() && shouldContinue() },
                canRecover = { power.isInteractive && !power.isDeviceIdleMode }
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
            closeRecorder()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(bufferSize: Int) {
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

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val callback = object : android.media.AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
                    configs.firstOrNull { it.clientAudioSessionId == audioRecord?.audioSessionId }?.let { observer?.onSilenced(it.isClientSilenced) }
                }
            }
            platformCallback = callback
            audioRecord?.registerAudioRecordingCallback(java.util.concurrent.Executor { it.run() }, callback)
        }
        audioRecord?.startRecording()
        if (android.os.Build.VERSION.SDK_INT >= 29) audioRecord?.activeRecordingConfiguration?.let { observer?.onSilenced(it.isClientSilenced) }
    }

    private fun closeRecorder() {
        // Only the capture worker owns release. A concurrent stop never frees
        // AudioRecord while a read is using native resources.
        try { audioRecord?.stop() } catch (e: IllegalStateException) { Log.e(TAG, "Error stopping AudioRecord", e) }
        if (android.os.Build.VERSION.SDK_INT >= 29) platformCallback?.let { audioRecord?.unregisterAudioRecordingCallback(it) }
        platformCallback = null
        audioRecord?.release()
        audioRecord = null
    }

    override fun resumeAfterSleep() { readLoop?.resumeAfterSleep() }

    override fun stop() {
        stopRequested.set(true)
        isRecording.set(false)
    }

    fun release() = stop()

    fun isRecording(): Boolean = isRecording.get()
}
