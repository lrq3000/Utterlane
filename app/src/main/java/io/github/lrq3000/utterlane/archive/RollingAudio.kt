package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.AudioPart
import java.io.File
import java.util.Properties

/** Constant-size retained layout. The compressed prefix owns [0, prefixFrames);
 * PCM chunks own [prefixFrames, frames). No implicit file-size/source-name clock. */
data class RollingAudio(val quality: AudioQuality, val partFrames: Long, val frames: Long = 0,
    val prefixFrames: Long = 0, val encodedName: String? = null, val complete: Boolean = false, val liveCapture: Boolean = true) {
    val format get() = quality.capture
    val durationMs get() = frames * 1000 / format.sampleRate
    val parts get() = (if (encodedName != null) 1 else 0) +
        if (complete) 0 else ((frames + partFrames - 1) / partFrames - prefixFrames / partFrames).toInt()
    fun asset(directory: File, index: Int, uri: String? = null): AudioPart {
        require(index in 0 until parts)
        if (encodedName != null && index == 0) return AudioPart(File(directory, encodedName), uri, quality.containerMime,
            format.recognitionSamples(prefixFrames), format.sampleRate, format.channels, 0, prefixFrames)
        val part = prefixFrames / partFrames + index - if (encodedName != null) 1 else 0
        val start = part * partFrames
        val count = minOf(partFrames, frames - start)
        return AudioPart(File(directory, "capture-$part.wav"), uri, "audio/wav", format.recognitionSamples(count),
            format.sampleRate, format.channels, start, count)
    }
    fun write(p: Properties) {
        p.setProperty("rolling", "true"); quality.writeTo(p); p.setProperty("partFrames", partFrames.toString())
        p.setProperty("captureFrames", frames.toString()); p.setProperty("prefixFrames", prefixFrames.toString())
        encodedName?.let { p.setProperty("encodedName", it) }; p.setProperty("encodedComplete", complete.toString())
        p.setProperty("rollingLiveCapture", liveCapture.toString())
    }
    companion object {
        fun read(p: Properties): RollingAudio? = if (p.getProperty("rolling") != "true") null else
            RollingAudio(AudioQuality.read(p), p.getProperty("partFrames").toLong(), p.getProperty("captureFrames", "0").toLong(),
                p.getProperty("prefixFrames", "0").toLong(), p.getProperty("encodedName"), p.getProperty("encodedComplete", "false").toBoolean(),
                p.getProperty("rollingLiveCapture", "true").toBoolean())
    }
}
