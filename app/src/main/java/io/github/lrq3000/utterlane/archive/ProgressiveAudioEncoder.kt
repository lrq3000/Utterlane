package io.github.lrq3000.utterlane.archive

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import java.util.Base64
import java.util.Properties

/** Runs exclusively on the low-priority archive worker. Disk publication is the
 * independent input cursor; no AudioRecord, ASR calls, Binder PCM arrays or parent
 * model stores are involved. An encoder exception never changes capture state. */
object ProgressiveAudioEncoder {
    fun encode(spool: RollingPcmJournal, output: File, encoderName: String? = null, checkActive: () -> Unit = {}) {
        val quality = spool.quality
        check(spool.state.encodingAllowed) { "Encoding was stopped; retained PCM and encoded prefix remain available" }
        val journal = File(spool.directory, "audio.frames")
        check(!journal.exists() || journal.length() == 0L) { "Interrupted encoded prefix is authoritative; use the retained mixed audio for recovery" }
        val selected = encoderName ?: checkNotNull(AudioEncoderCapabilities.encoder(quality)) { "Requested audio encoder is unavailable" }
        val calibration = if (quality.format == SavedAudioFormat.M4A) AacPrimingCalibration.measure(selected, quality) {
            checkActive(); check(spool.state.encodingAllowed) { "Encoding cancelled during calibration" }
        } else null
        calibration?.let { android.util.Log.i("ProgressiveEncoding", "encoder=$selected delay=${it.delayFrames} correlation=${it.correlation} decoder=${it.decoder}") }
        val codec = MediaCodec.createByCodecName(selected)
        var started = false
        try {
            codec.configure(AudioEncoderCapabilities.format(quality), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            EncodedJournal.Writer(journal).use { writer ->
                val info = MediaCodec.BufferInfo()
                var inputFrames = 0L
                var paddingInputFrames = 0
                var outputFrames = 0L
                var checkpointFrames = 0L
                var eos = false
                var ended = false
                val delay = calibration?.delayFrames ?: 0
                var config = ByteArray(0)
                var firstOutput = true
                var platformFirstPtsUs: Long? = null
                val packetFrames = if (quality.format == SavedAudioFormat.M4A) 1024 else quality.sampleRate / 50
                fun saveFormat() = AudioMetadata.write(File(spool.directory, "codec.properties"), Properties().apply {
                    quality.writeTo(this); setProperty("delay", delay.toString()); setProperty("config", Base64.getEncoder().encodeToString(config))
                    setProperty("paddingInputFrames", paddingInputFrames.toString())
                    setProperty("encoder", selected)
                    setProperty("delayMethod", if (calibration == null) "amr-storage-frames" else "codec-round-trip")
                    calibration?.let { setProperty("calibrationCorrelation", it.correlation.toString()); setProperty("decoder", it.decoder) }
                    platformFirstPtsUs?.let { setProperty("platformFirstPtsUs", it.toString()) }
                })
                while (!ended) {
                    checkActive()
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val available = spool.state
                    check(available.encodingAllowed) { "Encoding cancelled; preserving mixed audio" }
                    if (!eos && (inputFrames < available.frames || available.closed)) {
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(index)); buffer.clear(); buffer.order(ByteOrder.LITTLE_ENDIAN)
                            check(buffer.remaining() >= spool.format.bytesPerFrame) { "Encoder input buffer cannot hold a PCM frame" }
                            val count = minOf(3200L, available.frames - inputFrames, buffer.remaining().toLong() / spool.format.bytesPerFrame).toInt()
                            if (count > 0) {
                                val pcm = spool.readFrames(inputFrames, count)
                                buffer.asShortBuffer().put(pcm)
                                codec.queueInputBuffer(index, 0, pcm.size * 2, inputFrames * 1000000 / quality.sampleRate, 0)
                                inputFrames += count
                            } else {
                                val padding = if (calibration == null) 0 else AacEndPadding.frames(inputFrames, delay)
                                val count = minOf(3200, padding - paddingInputFrames, buffer.remaining() / spool.format.bytesPerFrame)
                                val pts = (inputFrames + paddingInputFrames) * 1000000 / quality.sampleRate
                                if (count > 0) {
                                    repeat(count * quality.channels) { buffer.putShort(0) }
                                    codec.queueInputBuffer(index, 0, count * spool.format.bytesPerFrame, pts, 0)
                                    paddingInputFrames += count
                                } else {
                                    codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    eos = true
                                }
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val actual = codec.outputFormat
                        check(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == quality.sampleRate &&
                            actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == quality.channels) { "Encoder changed the requested audio format" }
                        config = actual.getByteBuffer("csd-0")?.let { buffer ->
                            check(buffer.remaining() <= 64) { "Unsupported encoder configuration size" }
                            ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                        } ?: ByteArray(0)
                        check(calibration == null || config.contentEquals(calibration.config)) { "AAC configuration changed after priming calibration" }
                        saveFormat()
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (firstOutput) {
                                    check(quality.format != SavedAudioFormat.M4A || (config.isNotEmpty() && (config[0].toInt() and 0xff) ushr 3 == 2)) {
                                        "Encoder did not provide AAC-LC configuration; original PCM is preserved"
                                    }
                                    firstOutput = false; platformFirstPtsUs = info.presentationTimeUs; saveFormat()
                                }
                                check(info.size <= EncodedJournal.MAX_PACKET) { "Encoder packet exceeds the bounded journal format" }
                                // Stop the optional codec before checkpointing a
                                // prefix our final/repair container cannot index.
                                // Capture then keeps PCM, including this untouched
                                // tail; even the extreme-limit fallback is readable.
                                check(outputFrames / packetFrames < DiskIndexedAudioMp4.MAX_PACKETS) {
                                    "Audio container limit reached; retaining a recoverable encoded prefix and PCM tail"
                                }
                                val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size); buffer.get(bytes)
                                if (quality.format == SavedAudioFormat.THREE_GP) {
                                    AmrFrames.split(bytes, quality.sampleRate, outputFrames) { packet, frame ->
                                        check(outputFrames / packetFrames < DiskIndexedAudioMp4.MAX_PACKETS) { "Audio container limit reached" }
                                        writer.append(packet, frame * 1000000 / quality.sampleRate, packetFrames)
                                        outputFrames += packetFrames
                                    }
                                } else {
                                    // Provider PTS can start at zero or jump with
                                    // bundled input. Continuous AU order + measured
                                    // priming is the source clock, not that hint.
                                    writer.append(bytes, (outputFrames - delay) * 1000000 / quality.sampleRate, packetFrames)
                                    outputFrames += packetFrames
                                }
                            }
                            ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                        if (outputFrames - checkpointFrames >= quality.sampleRate || ended) {
                            val end = writer.checkpoint()
                            // Retain four AAC frames of decoder/context safety at
                            // a live edge. EOS owns exact source duration/padding.
                            val covered = minOf(inputFrames, (outputFrames - delay - if (ended) 0 else 4096).coerceAtLeast(0))
                            spool.checkpointEncoded(covered, end)
                            checkpointFrames = outputFrames
                        }
                    }
                }
                val finalSource = spool.state
                check(eos && finalSource.closed && inputFrames == finalSource.frames) { "Encoder ended before the capture source was drained" }
                check(outputFrames - delay >= inputFrames) { "Encoder did not produce the complete audio tail" }
                saveFormat()
                DiskIndexedAudioMp4.write(journal, spool.state.encodedBytes, output, quality, config, delay, inputFrames)
                AudioMetadata.write(File(spool.directory, "encoded-finished.properties"), Properties().apply {
                    setProperty("file", output.name); setProperty("frames", inputFrames.toString())
                })
                android.util.Log.i("ProgressiveEncoding", "encoder=$selected sourceFrames=$inputFrames paddingInputFrames=$paddingInputFrames outputFrames=$outputFrames delay=$delay")
            }
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
        }
    }
}
