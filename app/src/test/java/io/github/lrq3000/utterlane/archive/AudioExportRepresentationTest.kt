package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class AudioExportRepresentationTest {
    @get:Rule val temp = TemporaryFolder()
    private val jobs by lazy { ArchivalJobStore(temp.newFolder()) }
    private val history by lazy { RecordingHistory(temp.newFolder(), jobs) }
    private fun original(): HistoryEntry {
        val recording = history.begin(HistoryRetention.FOREVER)
        recording.append(shortArrayOf(123, -456, 32767)); recording.finish(false)
        return history.get(recording.entry.id)
    }
    private fun compress(entry: HistoryEntry) {
        history.planImportEncoding(entry.id, AudioQuality(compress = true), jobs.currentEpoch())
        val job = jobs.claim()!!
        AudioMetadata.write(File(entry.directory, "capture.properties"), Properties().apply {
            setProperty("frames", entry.samples.toString()); setProperty("closed", "true")
        })
        val output = File(entry.directory, job.targetName).apply { writeBytes(byteArrayOf(7, 8, 9)) }
        File(entry.directory, "audio.frames").writeBytes(byteArrayOf(1))
        jobs.target(job.id, job.generation, output.toURI().toString()); jobs.ready(job.id, job.generation)
        assertTrue(history.publishArchive(job))
    }
    @Test fun pickerSelectionKeepsWavMetadataAndBytesAcrossM4aPublication() {
        val entry = original(); val expected = entry.part(0).readBytes()
        val session = AudioExportSession()
        val selected = history.selectForExport(entry.id)
        session.install(selected)
        assertEquals("audio/wav", selected.mimeType); assertTrue(selected.name.endsWith(".wav"))
        compress(entry)
        assertEquals("audio/mp4", history.get(entry.id).asset(0).mimeType)
        val afterRecreation = session // the retained ViewModel still owns this session
        val destination = File(temp.newFolder(), selected.name)
        afterRecreation.take().use { snapshot -> destination.outputStream().use { snapshot.copyPartTo(0, it) } }
        assertArrayEquals(expected, destination.readBytes())
        assertFalse(history.hasLeases(entry.id))
    }
    @Test fun shareSnapshotReturnsMetadataForTheBytesItActuallyCopied() {
        val entry = original(); val expected = entry.part(0).readBytes()
        history.selectForExport(entry.id).use { selected ->
            compress(entry) // publication after selection, before export generation
            val snapshot = HistoryExports.createSnapshot(selected, temp.newFolder())
            assertEquals("audio/wav", snapshot.mimeType)
            assertEquals(selected.representation, snapshot.representation)
            assertTrue(snapshot.files.single().name.endsWith(".wav"))
            assertArrayEquals(expected, snapshot.files.single().readBytes())
        }
    }
    @Test fun cancellationAndLostProcessSelectionReleaseWithoutSubstitutingCurrentAudio() {
        val entry = original(); val session = AudioExportSession()
        session.install(history.selectForExport(entry.id)); compress(entry)
        assertTrue(history.hasLeases(entry.id)); session.cancel(); assertFalse(history.hasLeases(entry.id))
        assertThrows(IllegalStateException::class.java) { AudioExportSession().take() }
        val clearing = AudioExportSession(); clearing.install(history.selectForExport(entry.id)); clearing.close()
        assertFalse(history.hasLeases(entry.id))
    }
}
