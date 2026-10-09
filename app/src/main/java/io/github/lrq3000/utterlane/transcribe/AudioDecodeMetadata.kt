package io.github.lrq3000.utterlane.transcribe

import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException

/** Optional display metadata must not supply invented values for the PCM conversion contract. */
internal object AudioDecodeMetadata {
    data class Track(val index: Int, val format: MediaFormat, val mime: String)
    data class Pcm(val rate: Int, val channels: Int, val encoding: Int)

    fun audioTrack(extractor: MediaExtractor): Track {
        var firstFailure: Exception? = null
        for (index in 0 until extractor.trackCount) {
            val location = "Audio track $index"
            try {
                val format = read("$location descriptor") { extractor.getTrackFormat(index) }
                val mime = read("$location ${MediaFormat.KEY_MIME}") { format.getString(MediaFormat.KEY_MIME) }
                if (mime?.startsWith("audio/") != true) continue
                require(mime.length > "audio/".length) { "$location: invalid ${MediaFormat.KEY_MIME}" }
                positiveInteger(format, MediaFormat.KEY_SAMPLE_RATE, location)
                positiveInteger(format, MediaFormat.KEY_CHANNEL_COUNT, location)
                return Track(index, format, mime)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // One bad descriptor must not hide a later usable audio track. Keep
                // only one diagnostic rather than accumulating track-sized failures.
                if (firstFailure == null) firstFailure = e
            }
        }
        throw firstFailure ?: IllegalArgumentException("No audio track found")
    }

    fun durationUs(format: MediaFormat): Long = try {
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0) else 0
    } catch (e: CancellationException) { throw e
    } catch (_: RuntimeException) {
        // Duration drives progress only. Missing, mistyped or negative values mean
        // unknown duration; decoding still follows the codec's end-of-stream flag.
        0
    }

    fun pcm(format: MediaFormat, track: Int): Pcm {
        val location = "Audio track $track decoder output"
        val rate = positiveInteger(format, MediaFormat.KEY_SAMPLE_RATE, location)
        val channels = positiveInteger(format, MediaFormat.KEY_CHANNEL_COUNT, location)
        // Android's MediaFormat contract defines absent KEY_PCM_ENCODING as PCM16.
        // An explicitly supplied invalid encoding is not eligible for that default.
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
            read("$location ${MediaFormat.KEY_PCM_ENCODING}") { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
        else AudioFormat.ENCODING_PCM_16BIT
        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            "$location: unsupported ${MediaFormat.KEY_PCM_ENCODING}=$encoding"
        }
        return Pcm(rate, channels, encoding)
    }

    private fun positiveInteger(format: MediaFormat, key: String, location: String): Int {
        require(format.containsKey(key)) { "$location: missing $key" }
        val value = read("$location $key") { format.getInteger(key) }
        require(value > 0) { "$location: invalid $key=$value (must be positive)" }
        return value
    }

    private inline fun <T> read(location: String, value: () -> T): T = try { value() }
    catch (e: CancellationException) { throw e }
    catch (e: RuntimeException) { throw IllegalArgumentException("$location: unreadable metadata", e) }
}
