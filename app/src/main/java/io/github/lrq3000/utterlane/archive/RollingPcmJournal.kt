package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.HistoryMetadata
import io.github.lrq3000.utterlane.history.WavFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties

/** Scalar on-disk cursors, never a list of all chunks. Each process owns a distinct
 * metadata file: capture, codec checkpoint, parent-only stop fence, and single-owner
 * reclamation cursor (parent for live capture, worker for imported derivatives). The
 * codec must sync journal bytes BEFORE checkpointEncoded; reclamation commits the
 * replacement boundary BEFORE unlink. Extra old PCM after a crash is harmless. */
class RollingPcmJournal private constructor(val directory: File, val quality: AudioQuality, val partFrames: Long) {
    data class State(val frames: Long, val closed: Boolean, val reclaimedFrames: Long,
        val encodedFrames: Long, val encodedBytes: Long, val encodingAllowed: Boolean, val warning: String?)
    @Volatile private var frames = read("capture").getProperty("frames", "0").toLong()
    @Volatile private var closed = read("capture").getProperty("closed", "false").toBoolean()
    private var asr = 0L
    private var writer: WavFile? = null
    private var writerPart = -1L
    private var lastPublication = frames
    @Volatile private var preservationReason: String? = null
    private val control = PcmPreservation(directory)
    @Volatile private var reclaimedFrames = control.state().reclaimed
    val config get() = File(directory, "rolling.properties")
    val format get() = quality.capture
    val captureClosedOnDisk get() = read("capture").getProperty("closed", "false").toBoolean()
    val state: State get() {
        val capture = read("capture"); val encoded = read("encoded"); val preservation = control.state()
        return State(maxOf(frames, capture.getProperty("frames", "0").toLong()), closed || capture.getProperty("closed", "false").toBoolean(),
            preservation.reclaimed, encoded.getProperty("frames", "0").toLong(),
            encoded.getProperty("bytes", "0").toLong(), quality.encoding && preservationReason == null && !preservation.stopped,
            preservationReason ?: preservation.warning)
    }
    /** Writer-owned closure must not depend on reading optional worker metadata.
     * The reclaimer updates this cache only after its durable boundary commits. */
    fun captureLayout() = RollingAudio(quality, partFrames, frames, reclaimedFrames,
        if (reclaimedFrames > 0) "recovered-prefix.${quality.extension}" else null)
    fun pcm(index: Long) = File(directory, "capture-$index.wav")
    private fun read(name: String) = File(directory, "$name.properties").takeIf { it.isFile }?.let(HistoryMetadata::read) ?: Properties()
    private fun write(name: String, p: Properties) = AudioMetadata.write(File(directory, "$name.properties"), p)
    @Synchronized fun append(pcm: ShortArray) {
        check(!closed)
        require(pcm.size % format.channels == 0)
        var offset = 0
        while (offset < pcm.size) {
            val part = frames / partFrames
            if (part != writerPart) {
                writer?.close(); writer = WavFile(pcm(part), format.sampleRate, format.channels); writerPart = part
            }
            val count = minOf((pcm.size - offset).toLong() / format.channels, partFrames - frames % partFrames).toInt() * format.channels
            writer!!.append(if (offset == 0 && count == pcm.size) pcm else pcm.copyOfRange(offset, offset + count))
            frames += count / format.channels; offset += count
            // File data becomes independently visible at most once a second; the
            // in-process recognition publication remains per input block.
            if (frames - lastPublication >= format.sampleRate) publishCapture()
        }
    }
    private fun publishCapture() {
        writer?.sync()
        write("capture", Properties().apply { setProperty("frames", frames.toString()); setProperty("closed", closed.toString()) })
        lastPublication = frames
    }
    @Synchronized fun closeCapture() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        try { writer?.close() } catch (error: Exception) { failure = error } finally { writer = null }
        try { publishCapture() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
    /** Repair a failed EOF publication from the finalized capture writer's exact
     * source-frame count, after capture and codec writers are quiescent. Periodic
     * capture watermarks are lower bounds, never replacements for that final count.
     * This touches only the PCM tail header and capture closure, not codec bytes,
     * codec checkpoints, reclamation ownership or the retained prefix. */
    @Synchronized fun commitFinalizedCapture(finalizedFrames: Long) {
        require(finalizedFrames >= 0)
        check(writer == null) { "Cannot repair closure while a PCM writer is open" }
        val capture = read("capture")
        val checkpointFrames = capture.getProperty("frames", "0").toLong()
        check(checkpointFrames <= finalizedFrames) { "Capture checkpoint exceeds the finalized writer count" }
        if (capture.getProperty("closed", "false").toBoolean() && checkpointFrames == finalizedFrames) return
        val prefix = control.state().reclaimed
        check(prefix <= finalizedFrames) { "Reclaimed prefix exceeds the finalized capture" }
        if (finalizedFrames > prefix) {
            val lastPart = (finalizedFrames - 1) / partFrames
            val tail = pcm(lastPart)
            val requiredBytes = 44 + (finalizedFrames - lastPart * partFrames) * format.bytesPerFrame
            check(tail.isFile && tail.length() >= requiredBytes) { "Finalized PCM tail is unavailable; preserving recovery metadata" }
            WavFile.repair(tail, format.sampleRate, format.channels)
        }
        frames = finalizedFrames
        closed = true
        publishCapture() // must succeed before fallback publication/private unlink
    }
    /** A dead capture can have delivered bytes after its last periodic watermark.
     * Recover the physical tail from the absolute last checkpoint, never reset
     * the clock to the sizes of only the surviving PCM files. */
    fun recoverCapture() {
        var index = maxOf(reclaimedFrames / partFrames, frames / partFrames)
        while (true) {
            val file = pcm(index)
            if (!file.isFile) break
            val count = ((file.length() - 44).coerceAtLeast(0) / format.bytesPerFrame).coerceAtMost(partFrames)
            WavFile.repair(file, format.sampleRate, format.channels)
            frames = maxOf(frames, index * partFrames + count)
            if (count < partFrames) break
            index++
        }
        closed = true; publishCapture()
        preservePcm("Audio compression was interrupted. The retained encoded prefix and PCM tail are recoverable.")
    }
    /** Source clock, not queued codec input: only fully durable OUTPUT coverage. */
    fun checkpointEncoded(sourceFrames: Long, bytes: Long) {
        require(sourceFrames >= 0 && bytes >= 0)
        write("encoded", Properties().apply { setProperty("frames", sourceFrames.toString()); setProperty("bytes", bytes.toString()) })
    }
    @Synchronized fun commitAsr(processedSamples: Long, contextSamples: Long) {
        asr = maxOf(asr, (processedSamples - contextSamples).coerceAtLeast(0) * format.sampleRate / 16000)
    }
    fun preservePcm(reason: String) = synchronized(controlLock()) {
        preservationReason = reason
        control.preserve(reason.take(1024))
    }
    @Synchronized fun reclaim(): Int {
        val current = state
        if (!current.encodingAllowed) return 0
        val journal = File(directory, "audio.frames")
        if (current.encodedBytes <= 0 || !journal.isFile || journal.length() < current.encodedBytes) return 0
        val closedBoundary = if (closed) frames else ((frames - 1).coerceAtLeast(0) / partFrames) * partFrames
        val end = minOf(asr, current.encodedFrames, closedBoundary) / partFrames * partFrames
        var boundary = current.reclaimedFrames
        var count = 0
        while (boundary + partFrames <= end && count < 32) {
            if (control.state().stopped) break
            val next = boundary + partFrames
            control.advance(next)
            reclaimedFrames = next
            // Readers use absolute source-clock offsets. Persisting this first
            // means a lost unlink acknowledgement cannot create a recovery hole.
            val file = pcm(boundary / partFrames)
            check(!file.exists() || file.delete()) { "Cannot reclaim consumed PCM" }
            boundary = next; count++
        }
        return count
    }
    private fun controlLock() = controlLocks[(directory.absolutePath.hashCode() and Int.MAX_VALUE) % controlLocks.size]

    fun readFrames(offset: Long, count: Int): ShortArray {
        require(offset >= 0 && count >= 0)
        val output = ShortArray(count * format.channels)
        var cursor = offset; var copied = 0
        while (copied < count) {
            val n = minOf((count - copied).toLong(), partFrames - cursor % partFrames).toInt()
            RandomAccessFile(pcm(cursor / partFrames), "r").use { input ->
                input.seek(44 + cursor % partFrames * format.bytesPerFrame)
                val bytes = ByteArray(n * format.bytesPerFrame); input.readFully(bytes)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(output, copied * format.channels, n * format.channels)
            }
            copied += n; cursor += n
        }
        return output
    }
    companion object {
        private val controlLocks = Array(32) { Any() }
        fun create(directory: File, quality: AudioQuality, partSeconds: Int = if (quality.encoding) 30 else 3600,
            original: OriginalAudioSource? = null): RollingPcmJournal {
            require(quality.validTuple() && partSeconds > 0)
            check(!File(directory, "rolling.properties").exists()) { "Existing capture must not be overwritten" }
            val partFrames = partSeconds.toLong() * quality.capture.sampleRate
            AudioMetadata.write(File(directory, "rolling.properties"), Properties().apply {
                quality.writeTo(this); setProperty("partFrames", partFrames.toString())
                original?.let {
                    require(it.directory == directory)
                    EncodingInput.completeConversion(this, it)
                }
            })
            directory.parentFile?.let(AudioMetadata::syncDirectory)
            return RollingPcmJournal(directory, quality, partFrames)
        }
        fun open(directory: File): RollingPcmJournal {
            val config = HistoryMetadata.read(File(directory, "rolling.properties"))
            return RollingPcmJournal(directory, AudioQuality.read(config), config.getProperty("partFrames").toLong())
        }
    }
}
