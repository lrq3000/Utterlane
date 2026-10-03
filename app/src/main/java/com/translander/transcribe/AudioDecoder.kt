package com.translander.transcribe

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
            try {
                open(extractor)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("No audio track found")
                extractor.selectTrack(track)
                val inputFormat = extractor.getTrackFormat(track)
                val duration = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
                val decoder = MediaCodec.createDecoderByType(checkNotNull(inputFormat.getString(MediaFormat.KEY_MIME)))
                codec = decoder
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                var converter: StreamingResampler? = null
                var encoding = AudioFormat.ENCODING_PCM_16BIT
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
                        val format = decoder.outputFormat
                        converter = StreamingResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                        encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoder PCM encoding: $encoding" }
                    } else if (index >= 0) {
                        var samples: ShortArray? = null
                        try {
                            val buffer = decoder.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
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
                                // Some decoders do not announce the format before their first output.
                                if (converter == null) converter = StreamingResampler(inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
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
            } finally {
                try { codec?.stop() } finally { codec?.release(); extractor.release() }
            }
        }
}
