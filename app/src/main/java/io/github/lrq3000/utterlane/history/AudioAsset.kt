package io.github.lrq3000.utterlane.history

import java.io.File
import java.io.InputStream

/** One retained part. A document URI is opaque; it is never converted to a path.
 * PCM sample count is optional so future encoded parts need no pretend WAV layout. */
data class AudioPart(val local: File, val uri: String? = null, val mimeType: String = "audio/wav",
    val pcmSamples: Long? = null, val sampleRate: Int = if (pcmSamples != null) 16000 else 0,
    val channels: Int = if (pcmSamples != null) 1 else 0,
    val sourceStartFrame: Long = 0, val sourceFrames: Long? = null) {
    val name: String get() = local.name
    /** Reading preference, not ownership identity. While a lease still protects
     * staging, recognition/playback need not depend on an optional provider. */
    val readUri: String? get() = uri?.takeUnless { local.isFile }
    /** A compatible container suffix prevents SAF providers appending a second
     * extension to the journaled pending name. This never classifies compression
     * or transforms bytes; unknown containers keep a safe source suffix. */
    val archiveExtension: String get() = when (mimeType.lowercase(java.util.Locale.ROOT)) {
        "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "wav"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/aac", "audio/aac-adts" -> "aac"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/3gpp" -> "3ga"
        "audio/amr" -> "amr"
        else -> local.extension.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(8).ifEmpty { "audio" }
    }
}

interface AudioAssetIO {
    /** Metadata-only; may run under publication locks. No provider calls here. */
    fun available(part: AudioPart): Boolean
    /** Blocking IO outside every history lock; caller holds a source lease. */
    fun open(part: AudioPart): InputStream
}

object PrivateAudioAssetIO : AudioAssetIO {
    override fun available(part: AudioPart) = part.local.isFile
    override fun open(part: AudioPart): InputStream {
        check(part.readUri == null) { "External audio requires a document adapter" }
        return part.local.inputStream()
    }
}
