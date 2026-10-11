package io.github.lrq3000.utterlane.archive

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Capability measured with the exact encoder/configuration, not inferred from
 * its brand or PTS. Probe bytes never enter a recording. Entire work runs in the
 * low-priority killable codec worker while capture/ASR use the original spool. */
object AacPrimingCalibration {
    data class Result(val delayFrames: Int, val correlation: Double, val config: ByteArray, val decoder: String)
    private const val MAX_PACKETS = 64
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_OUTPUT = PcmPrimingEstimator.PROBE_FRAMES + PcmPrimingEstimator.MAX_DELAY + 8192

    fun measure(encoderName: String, quality: AudioQuality, checkActive: () -> Unit = {}): Result {
        require(quality.format == SavedAudioFormat.M4A && quality.encoding)
        val deadline = System.nanoTime() + 20_000_000_000L
        fun alive() {
            checkActive()
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            check(System.nanoTime() < deadline) { "AAC calibration timed out; PCM is preserved" }
        }
        val reference = PcmPrimingEstimator.probe(quality.sampleRate)
        // Delay is not known yet. Supply the bounded search range as real codec
        // input padding so a valid delayed source tail can emerge before EOS.
        val paddingFrames = AacEndPadding.frames(reference.size.toLong(), PcmPrimingEstimator.MAX_DELAY)
        val packets = ArrayList<ByteArray>() // strictly capped by count AND bytes below
        var config = ByteArray(0)
        var encoderDelay: Int? = null
        var encoderPadding: Int? = null
        var firstPacketPtsUs: Long? = null
        var lastPacketPtsUs: Long? = null
        var encoderEosBytes = -1
        var decoderEosBytes = -1
        val encoder = MediaCodec.createByCodecName(encoderName)
        var started = false
        try {
            encoder.configure(AudioEncoderCapabilities.format(quality), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start(); started = true
            val info = MediaCodec.BufferInfo()
            var frames = 0; var eos = false; var ended = false; var bytes = 0
            while (!ended) {
                alive()
                if (!eos) {
                    val index = encoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = checkNotNull(encoder.getInputBuffer(index)).apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        check(buffer.remaining() >= quality.channels * 2)
                        val count = minOf(1024, reference.size + paddingFrames - frames, buffer.remaining() / (quality.channels * 2))
                        if (count > 0) {
                            repeat(count) { n ->
                                val sample = if (frames + n < reference.size) (reference[frames + n] * 32768).toInt().toShort() else 0.toShort()
                                repeat(quality.channels) { buffer.putShort(sample) }
                            }
                            encoder.queueInputBuffer(index, 0, count * quality.channels * 2, frames * 1000000L / quality.sampleRate, 0)
                            frames += count
                        } else {
                            encoder.queueInputBuffer(index, 0, 0, frames * 1000000L / quality.sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = encoder.outputFormat
                    encoderDelay = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) format.getInteger(MediaFormat.KEY_ENCODER_DELAY) else null
                    encoderPadding = if (format.containsKey(MediaFormat.KEY_ENCODER_PADDING)) format.getInteger(MediaFormat.KEY_ENCODER_PADDING) else null
                    check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == quality.sampleRate && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == quality.channels)
                    val csd = checkNotNull(format.getByteBuffer("csd-0")).duplicate()
                    check(csd.remaining() in 1..64)
                    config = ByteArray(csd.remaining()).also(csd::get)
                    check((config[0].toInt() and 255) ushr 3 == 2) { "Calibration requires AAC-LC" }
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(info.size <= EncodedJournal.MAX_PACKET && packets.size < MAX_PACKETS && bytes + info.size <= MAX_BYTES)
                            val buffer = checkNotNull(encoder.getOutputBuffer(index)).duplicate()
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            packets += ByteArray(info.size).also(buffer::get); bytes += info.size
                            if (firstPacketPtsUs == null) firstPacketPtsUs = info.presentationTimeUs
                            lastPacketPtsUs = info.presentationTimeUs
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (ended) encoderEosBytes = info.size
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
            }
        } finally { try { if (started) encoder.stop() } finally { encoder.release() } }
        check(config.isNotEmpty() && packets.isNotEmpty())

        val decoder = MediaCodec.createDecoderByType(quality.codecMime)
        val decoderName = decoder.name
        val decoded = FloatArray(MAX_OUTPUT)
        var decodedFrames = 0
        started = false
        try {
            // Deliberately raw: no encoder-delay/padding metadata or edit-list
            // timestamps. The measured delay includes the actual round trip.
            val input = MediaFormat.createAudioFormat(quality.codecMime, quality.sampleRate, quality.channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
                setInteger(MediaFormat.KEY_ENCODER_DELAY, 0); setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
            }
            decoder.configure(input, null, null, 0); decoder.start(); started = true
            val info = MediaCodec.BufferInfo()
            var next = 0; var eos = false; var ended = false
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            while (!ended) {
                alive()
                if (!eos) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        if (next < packets.size) {
                            val packet = packets[next]; check(buffer.remaining() >= packet.size); buffer.put(packet)
                            decoder.queueInputBuffer(index, 0, packet.size, next * 1024L * 1000000 / quality.sampleRate, 0); next++
                        } else { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == quality.sampleRate && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == quality.channels)
                    encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    check(encoding in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
                } else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            val buffer = checkNotNull(decoder.getOutputBuffer(index)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val frameBytes = quality.channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            check(info.size % frameBytes == 0 && decodedFrames + info.size / frameBytes <= decoded.size)
                            while (buffer.remaining() >= frameBytes) {
                                var value = 0f
                                repeat(quality.channels) { value += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float else buffer.short / 32768f }
                                decoded[decodedFrames++] = value / quality.channels
                            }
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (ended) decoderEosBytes = info.size
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
        } finally { try { if (started) decoder.stop() } finally { decoder.release() } }
        check(decodedFrames == packets.size * 1024) { "AAC encoder did not expose one LC access unit per packet" }
        val evidence = PcmPrimingEstimator.measure(reference, decoded.copyOf(decodedFrames), ::alive)
        val diagnostic = "encoder=$encoderName decoder=$decoderName rate=${quality.sampleRate} channels=${quality.channels} " +
            "input=${reference.size} padding=$paddingFrames packets=${packets.size} decoded=$decodedFrames delay=${evidence.frames} correlation=${evidence.correlation} " +
            "delayKey=$encoderDelay paddingKey=$encoderPadding pts=$firstPacketPtsUs..$lastPacketPtsUs encoderEosBytes=$encoderEosBytes decoderEosBytes=$decoderEosBytes"
        android.util.Log.i("ProgressiveEncoding", "AAC calibration: $diagnostic")
        check(decodedFrames - evidence.frames >= reference.size) { "AAC calibration lost the final input tail: $diagnostic" }
        return Result(evidence.frames, evidence.correlation, config, decoderName)
    }
}
