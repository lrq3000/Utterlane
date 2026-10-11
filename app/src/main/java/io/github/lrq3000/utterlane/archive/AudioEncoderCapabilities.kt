package io.github.lrq3000.utterlane.archive

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

object AudioEncoderCapabilities {
    fun format(quality: AudioQuality): MediaFormat = MediaFormat.createAudioFormat(quality.codecMime, quality.sampleRate, quality.channels).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, quality.effectiveBitRate)
        if (quality.format == SavedAudioFormat.M4A) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
    }
    fun encoder(quality: AudioQuality): String? {
        if (!quality.encoding || !quality.validTuple()) return null
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals(quality.codecMime, true) } && runCatching {
                    val caps = codec.getCapabilitiesForType(quality.codecMime)
                    val audio = checkNotNull(caps.audioCapabilities)
                    audio.isSampleRateSupported(quality.sampleRate) &&
                        quality.channels <= audio.maxInputChannelCount &&
                        audio.bitrateRange.contains(quality.effectiveBitRate) && caps.isFormatSupported(format(quality))
                }.getOrDefault(false)
            }?.name
        } catch (_: Exception) { null }
    }
}
