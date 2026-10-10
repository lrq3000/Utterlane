package io.github.lrq3000.utterlane.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.AudioRecorder

/** Check platform declarations before admitting PCM to the fixed-format writer. */
internal object MicrophoneCaptureConfiguration {
    fun problem(context: Context, record: AudioRecord, options: MicrophoneOptions): String? {
        if (record.sampleRate != AudioRecorder.SAMPLE_RATE || record.channelCount != 1 ||
            record.audioFormat != AudioFormat.ENCODING_PCM_16BIT) {
            return context.getString(R.string.microphone_pcm_not_applied,
                record.sampleRate, record.channelCount, record.audioFormat)
        }
        // DEFAULT deliberately delegates source choice to Android. Every named
        // source must remain the explicitly selected source, including on reopen.
        if (options.source != MicrophoneSource.DEFAULT && record.audioSource != options.source.androidSource) {
            return context.getString(R.string.microphone_source_not_applied, options.source.name, record.audioSource)
        }
        if (options.source == MicrophoneSource.UNPROCESSED) {
            val supported = runCatching {
                context.getSystemService(AudioManager::class.java)
                    .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            }.getOrNull()
            if (supported != "true") return context.getString(R.string.microphone_unprocessed_unavailable)
        }
        if (record.bufferSizeInFrames <= 0) return context.getString(R.string.microphone_buffer_unavailable)
        return null
    }
}
