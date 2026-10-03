package com.translander.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
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

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ).coerceAtLeast(SAMPLE_RATE * 2) // At least 1 second buffer

    @SuppressLint("MissingPermission")
    override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
        if (stopRequested.get()) return
        if (!isRecording.compareAndSet(false, true)) return

        try {
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

            audioRecord?.startRecording()

            // Capture and publish 200 ms at a time; no recording-long audio list.
            val buffer = ShortArray(SAMPLE_RATE / 5)

            while (isRecording.get() && shouldContinue()) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readCount > 0) {
                    onSamples(buffer.copyOfRange(0, readCount))
                } else if (readCount < 0) {
                    Log.e(TAG, "AudioRecord read error: $readCount")
                    error("AudioRecord read error: $readCount")
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            audioRecord?.release()
            audioRecord = null
            throw e
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord illegal state", e)
            audioRecord?.release()
            audioRecord = null
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord permission denied", e)
            audioRecord?.release()
            audioRecord = null
            throw e
        } finally {
            isRecording.set(false)
            // Only the capture worker owns release. A concurrent stop never frees
            // AudioRecord while its blocking read is using native resources.
            try { audioRecord?.stop() } catch (e: IllegalStateException) { Log.e(TAG, "Error stopping AudioRecord", e) }
            audioRecord?.release()
            audioRecord = null
        }
    }

    override fun stop() {
        stopRequested.set(true)
        isRecording.set(false)
    }

    fun release() = stop()

    fun isRecording(): Boolean = isRecording.get()
}
