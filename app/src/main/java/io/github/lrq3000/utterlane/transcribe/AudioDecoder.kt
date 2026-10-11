package io.github.lrq3000.utterlane.transcribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Incremental MediaCodec decoding: no list of file-long PCM, mono or resampled arrays. */
class AudioDecoder(private val context: Context) {
    suspend fun decodePart(part: io.github.lrq3000.utterlane.history.AudioPart, onSamples: suspend (ShortArray) -> Unit) {
        var emitted = 0L
        val accept: suspend (ShortArray) -> Unit = { pcm ->
            val count = minOf(pcm.size.toLong(), (part.pcmSamples ?: Long.MAX_VALUE) - emitted).coerceAtLeast(0).toInt()
            if (count > 0) { onSamples(if (count == pcm.size) pcm else pcm.copyOf(count)); emitted += count }
        }
        if (part.readUri != null) decode(Uri.parse(part.readUri), accept, timelineTrim = part.sourceFrames != null)
        else decode(part.local.absolutePath, accept, timelineTrim = part.sourceFrames != null)
        part.pcmSamples?.let { check(emitted == it) { "Saved audio is shorter than its source-clock metadata" } }
    }
    suspend fun decode(uri: Uri, onSamples: suspend (ShortArray) -> Unit, onProgress: (Int?) -> Unit = {}, timelineTrim: Boolean = false) =
        decodeSource({ extractor ->
            context.contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: error("Cannot open audio file")
        }, onSamples, onProgress, timelineTrim = timelineTrim)

    suspend fun decode(path: String, onSamples: suspend (ShortArray) -> Unit, onProgress: (Int?) -> Unit = {}, timelineTrim: Boolean = false) =
        decodeSource({ it.setDataSource(path) }, onSamples, onProgress, timelineTrim = timelineTrim)

    suspend fun decodeTo(path: String, format: io.github.lrq3000.utterlane.archive.PcmFormat, onSamples: suspend (ShortArray) -> Unit) =
        decodeSource({ it.setDataSource(path) }, onSamples, {}, format)
    suspend fun decodeTo(part: io.github.lrq3000.utterlane.history.AudioPart, format: io.github.lrq3000.utterlane.archive.PcmFormat,
        onSamples: suspend (ShortArray) -> Unit) = decodeSource({ extractor ->
        if (part.readUri == null) extractor.setDataSource(part.local.path)
        else context.contentResolver.openFileDescriptor(Uri.parse(part.readUri), "r")?.use { extractor.setDataSource(it.fileDescriptor) }
            ?: error("Cannot open original audio")
    }, onSamples, {}, format)

    private suspend fun decodeSource(open: (MediaExtractor) -> Unit, onSamples: suspend (ShortArray) -> Unit, onProgress: (Int?) -> Unit,
        target: io.github.lrq3000.utterlane.archive.PcmFormat = io.github.lrq3000.utterlane.archive.PcmFormat(), timelineTrim: Boolean = false) =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var codecStarted = false
            var failure: Throwable? = null
            try {
                open(extractor)
                val track = AudioDecodeMetadata.audioTrack(extractor)
                extractor.selectTrack(track.index)
                val inputFormat = track.format
                val duration = AudioDecodeMetadata.durationUs(track.format)
                var ownedSkip: Long? = if (timelineTrim && inputFormat.containsKey(MediaFormat.KEY_ENCODER_DELAY))
                    inputFormat.getInteger(MediaFormat.KEY_ENCODER_DELAY).toLong().coerceAtLeast(0) else null
                if (timelineTrim) {
                    // Owned containers have a measured edit-list delay. Apply it
                    // once here, not both in CCodec's skip-cut buffer and again
                    // through negative PTS. This also handles clamped output PTS.
                    inputFormat.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
                    inputFormat.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
                }
                val decoder = MediaCodec.createDecoderByType(track.mime)
                codec = decoder
                decoder.configure(track.format, null, null, 0)
                decoder.start()
                codecStarted = true
                var converter: io.github.lrq3000.utterlane.archive.ArchivePcmConverter? = null
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var outputRate = 0
                var outputChannels = 0
                fun configureOutput() {
                    val pcm = AudioDecodeMetadata.pcm(decoder.outputFormat, track.index)
                    outputRate = pcm.rate
                    outputChannels = pcm.channels
                    converter = io.github.lrq3000.utterlane.archive.ArchivePcmConverter(
                        io.github.lrq3000.utterlane.archive.PcmFormat(pcm.rate, pcm.channels), target)
                    encoding = pcm.encoding
                }
                var inputDone = false
                var outputDone = false
                var lastProgress: Int? = null
                val info = MediaCodec.BufferInfo()
                onProgress(if (duration > 0) 0 else null)
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = checkNotNull(decoder.getInputBuffer(index))
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        converter?.finish()?.let { if (it.isNotEmpty()) onSamples(it) }
                        configureOutput()
                    } else if (index >= 0) {
                        var samples: ShortArray? = null
                        try {
                            val buffer = decoder.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                // Some decoders omit the initial format event. Read
                                // their actual output, including encoding, before
                                // interpreting bytes; compressed input is not PCM.
                                if (converter == null) configureOutput()
                                val frameBytes = outputChannels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                if (timelineTrim && ownedSkip == null) ownedSkip =
                                    ((-info.presentationTimeUs).coerceAtLeast(0) * outputRate + 500000) / 1000000
                                val skipFrames = if (timelineTrim) minOf(ownedSkip ?: 0, info.size.toLong() / frameBytes) else 0
                                val skipBytes = (skipFrames * frameBytes).toInt()
                                if (timelineTrim) ownedSkip = (ownedSkip ?: 0) - skipFrames
                                buffer.position(info.offset + skipBytes)
                                buffer.limit(info.offset + info.size)
                                buffer.order(ByteOrder.nativeOrder())
                                val normalized = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val floats = buffer.asFloatBuffer()
                                    FloatArray(floats.remaining()).also { floats.get(it) }
                                } else {
                                    val shorts = buffer.asShortBuffer()
                                    FloatArray(shorts.remaining()) { shorts.get() / 32768f }
                                }
                                samples = converter!!.accept(normalized)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                        // Release the codec buffer before recognition suspends this producer.
                        samples?.let { if (it.isNotEmpty()) onSamples(it) }
                        if (duration > 0) {
                            val progress = (info.presentationTimeUs * 100 / duration).toInt().coerceIn(0, 99)
                            if (progress != lastProgress) { lastProgress = progress; onProgress(progress) }
                        }
                    }
                }
                converter?.finish()?.let { if (it.isNotEmpty()) onSamples(it) }
                onProgress(100)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                releaseResources(codec, codecStarted, extractor, failure)
            }
        }

    private fun releaseResources(codec: MediaCodec?, started: Boolean, extractor: MediaExtractor, original: Throwable?) {
        var failure = original
        fun release(action: () -> Unit) {
            try { action() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup
                else if (failure !== cleanup) failure!!.addSuppressed(cleanup)
            }
        }
        // All owners must be released even if stop/release throws. In particular,
        // cleanup must not turn cancellation or a storage failure into a codec error.
        if (started) release { codec?.stop() }
        release { codec?.release() }
        release { extractor.release() }
        if (original == null) failure?.let { throw it }
    }
}
