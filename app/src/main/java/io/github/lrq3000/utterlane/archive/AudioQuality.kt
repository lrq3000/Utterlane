package io.github.lrq3000.utterlane.archive

import java.util.Properties

data class PcmFormat(val sampleRate: Int = 16000, val channels: Int = 1) {
    val native get() = sampleRate == 16000 && channels == 1
    val bytesPerFrame get() = channels * 2
    fun recognitionSamples(frames: Long) = (frames * 16000 + sampleRate - 1) / sampleRate
}
enum class SavedAudioFormat { M4A, THREE_GP, WAV }

/** Preferences are requests, never evidence of actual device support. */
data class AudioQuality(val compress: Boolean = false, val format: SavedAudioFormat = SavedAudioFormat.M4A,
    val sampleRate: Int = 16000, val channels: Int = 1, val bitRate: Int = 96000) {
    val encoding get() = compress && format != SavedAudioFormat.WAV
    val capture get() = if (compress) PcmFormat(sampleRate, channels) else PcmFormat()
    val effectiveBitRate get() = if (format == SavedAudioFormat.THREE_GP) {
        if (sampleRate == 8000) 12200 else 23850
    } else bitRate
    val codecMime get() = if (format == SavedAudioFormat.THREE_GP) {
        if (sampleRate == 8000) "audio/3gpp" else "audio/amr-wb"
    } else "audio/mp4a-latm"
    val containerMime get() = if (format == SavedAudioFormat.THREE_GP) "audio/3gpp" else "audio/mp4"
    val extension get() = if (format == SavedAudioFormat.THREE_GP) "3gp" else "m4a"
    fun validTuple(): Boolean = sampleRate in SAMPLE_RATES && channels in 1..2 && when (format) {
        SavedAudioFormat.WAV -> true
        SavedAudioFormat.M4A -> bitRate in BIT_RATES && bitRate <= 6 * sampleRate * channels
        SavedAudioFormat.THREE_GP -> channels == 1 && sampleRate in listOf(8000, 16000)
    }
    fun shouldEncodeImport(isCompressed: Boolean?, retained: Boolean) = encoding && retained && isCompressed == false
    fun writeTo(p: Properties) {
        p.setProperty("compress", compress.toString()); p.setProperty("format", format.name)
        p.setProperty("rate", sampleRate.toString()); p.setProperty("channels", channels.toString()); p.setProperty("bitrate", bitRate.toString())
    }
    companion object {
        val SAMPLE_RATES = listOf(8000, 16000, 22050, 32000, 44100, 48000)
        val BIT_RATES = listOf(48000, 96000, 128000, 192000, 256000, 288000)
        fun read(p: Properties) = AudioQuality(p.getProperty("compress", "false").toBoolean(),
            SavedAudioFormat.valueOf(p.getProperty("format", "M4A")), p.getProperty("rate", "16000").toInt(),
            p.getProperty("channels", "1").toInt(), p.getProperty("bitrate", "96000").toInt())
    }
}
