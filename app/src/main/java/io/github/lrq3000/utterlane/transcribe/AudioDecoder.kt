package io.github.lrq3000.utterlane.transcribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Incremental MediaCodec decoding: no list of file-long PCM, mono or resampled arrays. */
class AudioDecoder(private val context: Context) {
    suspend fun decode(uri: Uri, onSamples: suspend (ShortArray) -> Unit, onProgress: (Int?) -> Unit = {}) =
        decodeSource({ extractor ->
            context.contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: error("Cannot open audio file")
        }, onSamples, onProgress)

    suspend fun decode(path: String, onSamples: suspend (ShortArray) -> Unit, onProgress: (Int?) -> Unit = {}) =
        decodeSource({ it.setDataSource(path) }, onSamples, onProgress)

    private suspend fun decodeSource(open: (MediaExtractor) -> Unit, onSamples: suspend (ShortArray) -> Unit, onProgress: (Int?) -> Unit) =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var codecStarted = false
            var failure: Throwable? = null
            try {
                open(extractor)
                val track = AudioDecodeMetadata.audioTrack(extractor)
                extractor.selectTrack(track.index)
                val duration = AudioDecodeMetadata.durationUs(track.format)
                val decoder = MediaCodec.createDecoderByType(track.mime)
                codec = decoder
                decoder.configure(track.format, null, null, 0)
                decoder.start()
                codecStarted = true
                var converter: StreamingResampler? = null
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                fun configureOutput() {
                    val pcm = AudioDecodeMetadata.pcm(decoder.outputFormat, track.index)
                    converter = StreamingResampler(pcm.rate, pcm.channels)
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
                                buffer.position(info.offset)
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
