package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties

/** Immutable original representation, independent of conversion's derivative
 * spool. URI bindings are separate per-part records, so multipart originals need
 * neither a Binder array nor a file-long list. Callers hold the audio owner's lease. */
class OriginalAudioSource private constructor(val directory: File, val representation: String,
    val sourceName: String?, val mimeType: String, val samples: Long, val parts: Int,
    val partSamples: Long, val durationMs: Long) {
    val nativePcm get() = sourceName == null
    fun asset(part: Int): AudioPart {
        require(part in 0 until parts)
        val binding = File(directory, "original-source/$part.properties")
        return AudioPart(File(directory, sourceName ?: "audio-$part.wav"),
            binding.takeIf(File::isFile)?.let { ArchiveDisk.read(it).getProperty("uri") }, mimeType,
            if (nativePcm) minOf(partSamples, samples - part * partSamples) else null)
    }
    internal fun bind(part: Int, uri: String) {
        require(part in 0 until parts)
        val file = File(directory, "original-source/$part.properties")
        if (!file.isFile || ArchiveDisk.read(file).getProperty("uri") != uri)
            AudioMetadata.write(file, Properties().apply { setProperty("uri", uri) })
        // A late binding can create original-source/ after the manifest already
        // committed. Persist that directory entry before private PCM can unlink.
        AudioMetadata.syncDirectory(directory)
    }
    fun pcmReader(io: AudioAssetIO, start: Long = 0, lease: Closeable? = null): PcmReader {
        require(nativePcm && start in 0..samples)
        return PcmReader(io, start, lease)
    }
    inner class PcmReader internal constructor(private val io: AudioAssetIO, start: Long, private val lease: Closeable?) : Closeable {
        var offset = start
            private set
        private var part = -1
        private var input: DataInputStream? = null
        private var closed = false
        fun read(count: Int = 3200): ShortArray {
            require(count in 1..3200)
            check(!closed)
            val result = ShortArray(minOf(count.toLong(), samples - offset).toInt())
            var copied = 0
            while (copied < result.size) {
                val index = (offset / partSamples).toInt()
                if (index != part) {
                    input?.close(); input = null
                    val raw = io.open(asset(index))
                    try {
                        var skip = 44 + offset % partSamples * 2
                        while (skip > 0) {
                            val n = raw.skip(skip)
                            if (n > 0) skip -= n else { check(raw.read() >= 0); skip-- }
                        }
                        input = DataInputStream(raw); part = index
                    } catch (error: Exception) { raw.close(); throw error }
                }
                val n = minOf((result.size - copied).toLong(), partSamples - offset % partSamples).toInt()
                val bytes = ByteArray(n * 2); input!!.readFully(bytes)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(result, copied, n)
                copied += n; offset += n
            }
            return result
        }
        override fun close() {
            if (closed) return
            closed = true
            try { input?.close(); input = null } finally { lease?.close() }
        }
    }
    companion object {
        private fun manifest(directory: File) = File(directory, "original-source.properties")
        fun open(directory: File): OriginalAudioSource? = manifest(directory).takeIf(File::isFile)?.let {
            val p = ArchiveDisk.read(it)
            OriginalAudioSource(directory, p.getProperty("representation", AudioRepresentation.ORIGINAL), p.getProperty("source"),
                p.getProperty("mime"), p.getProperty("samples").toLong(), p.getProperty("parts").toInt(),
                p.getProperty("partSamples").toLong(), p.getProperty("durationMs").toLong())
        }
        internal fun capture(entry: HistoryEntry): OriginalAudioSource {
            open(entry.directory)?.let { return it }
            require(entry.rollingAudio == null)
            val source = OriginalAudioSource(entry.directory, entry.representation, entry.sourceName, entry.mimeType,
                entry.samples, entry.parts, RecordingHistory.PART_SAMPLES, entry.durationMs)
            entry.externalParts.forEach(source::bind)
            save(source)
            return source
        }
        /** Upgrade pre-descriptor conversions while their original PCM/URI is
         * still owned. Never mistake a live rolling capture's reclaimed prefix
         * for a complete original; only the explicit old import descriptor qualifies. */
        internal fun recoverLegacy(directory: File, recording: Properties): OriginalAudioSource? {
            open(directory)?.let { return it }
            if (File(directory, "pcm-retired.properties").isFile) return null
            val config = File(directory, "rolling.properties").takeIf(File::isFile)?.let(ArchiveDisk::read) ?: return null
            val input = config.getProperty("importSource") ?: return null
            val pcmParts = config.getProperty("importPcmParts")?.toInt()
            val samples = recording.getProperty("samples", "0").toLong()
            val source = OriginalAudioSource(directory, config.getProperty("sourceRepresentation", AudioRepresentation.ORIGINAL),
                if (pcmParts != null) null else input, if (pcmParts != null) "audio/wav" else recording.getProperty("importMime", "audio/*"),
                samples, pcmParts ?: 1, RecordingHistory.PART_SAMPLES,
                if (pcmParts != null) samples * 1000 / 16000 else recording.getProperty("durationMs", "0").toLong())
            save(source)
            return source
        }
        private fun save(source: OriginalAudioSource) {
            AudioMetadata.write(manifest(source.directory), Properties().apply {
                setProperty("representation", source.representation); source.sourceName?.let { setProperty("source", it) }
                setProperty("mime", source.mimeType); setProperty("samples", source.samples.toString())
                setProperty("parts", source.parts.toString()); setProperty("partSamples", source.partSamples.toString())
                setProperty("durationMs", source.durationMs.toString())
            })
        }
    }
}
