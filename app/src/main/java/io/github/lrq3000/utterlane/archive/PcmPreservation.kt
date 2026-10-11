package io.github.lrq3000.utterlane.archive

import java.io.File
import java.util.Properties

/** Split authorities, not a cross-process read/modify/write record.
 * The parent alone installs the immutable stop fence. The sole reclaimer writes
 * its cursor: parent for live capture, decoder worker for import conversion.
 * Distinct records also mean distinct atomic-write temporaries. Legacy combined
 * metadata is read-only and remains a monotonic floor during upgrade. */
internal class PcmPreservation(private val directory: File,
    private val write: (File, Properties) -> Unit = AudioMetadata::write) {
    data class State(val reclaimed: Long, val stopped: Boolean, val warning: String?)
    private val stopFile get() = File(directory, "preservation-stop.properties")
    private val cursorFile get() = File(directory, "reclamation.properties")
    private fun read(file: File) = file.takeIf(File::isFile)?.let(ArchiveDisk::read) ?: Properties()
    fun state(): State {
        val legacy = read(File(directory, "preservation.properties"))
        val stop = read(stopFile)
        return State(maxOf(legacy.getProperty("reclaimed", "0").toLong(), read(cursorFile).getProperty("reclaimed", "0").toLong()),
            stopFile.isFile || legacy.getProperty("stopped", "false").toBoolean(), stop.getProperty("warning") ?: legacy.getProperty("warning"))
    }
    fun preserve(reason: String) {
        if (stopFile.isFile) return
        write(stopFile, Properties().apply { setProperty("stopped", "true"); setProperty("warning", reason) })
    }
    fun advance(frames: Long) {
        require(frames >= state().reclaimed) { "Reclamation cursor cannot move backwards" }
        write(cursorFile, Properties().apply { setProperty("reclaimed", frames.toString()) })
    }
}
