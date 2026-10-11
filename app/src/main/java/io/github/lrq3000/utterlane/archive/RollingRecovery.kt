package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.HistoryMetadata
import java.io.File
import java.util.Base64

/** Recovery is a codec-free remux of the authoritative journal. It never retries
 * an encoder, changes its prefix, or consumes/removes the raw tail. Consumers
 * invoke this on IO before opening the projected prefix container. */
object RollingRecovery {
    private val locks = Array(32) { Any() }
    fun ensurePrefix(directory: File, layout: RollingAudio) {
        if (layout.complete || layout.encodedName == null) return
        val output = File(directory, layout.encodedName)
        synchronized(locks[(directory.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]) {
            if (output.isFile) return
            val spool = RollingPcmJournal.open(directory)
            val codec = HistoryMetadata.read(File(directory, "codec.properties"))
            val temporary = File(directory, "repair.partial")
            try {
                DiskIndexedAudioMp4.write(File(directory, "audio.frames"), spool.state.encodedBytes, temporary, layout.quality,
                    Base64.getDecoder().decode(codec.getProperty("config")), codec.getProperty("delay").toInt(), layout.prefixFrames)
                ArchiveDisk.move(temporary, output)
                AudioMetadata.syncDirectory(directory)
            } finally { temporary.delete() }
        }
    }
    fun layout(spool: RollingPcmJournal): RollingAudio {
        val state = spool.state
        return RollingAudio(spool.quality, spool.partFrames, state.frames, state.reclaimedFrames,
            if (state.reclaimedFrames > 0) "recovered-prefix.${spool.quality.extension}" else null)
    }
}
