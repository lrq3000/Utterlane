package io.github.lrq3000.utterlane

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lrq3000.utterlane.archive.*
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties
import java.util.UUID

/** Real Intent/ContentResolver boundary; all data and ledger are test-owned. */
@RunWith(AndroidJUnit4::class)
class AudioExportSnapshotAndroidTest {
    @Test fun createDocumentMetadataAndExportBytesStayWithThePickerSelection() {
        val app = ApplicationProvider.getApplicationContext<UtterlaneApp>()
        val root = File(app.cacheDir, "export-snapshot-${UUID.randomUUID()}").apply { mkdirs() }
        val jobs = ArchivalJobStore(File(root, "jobs"))
        try {
            val history = RecordingHistory(File(root, "history"), jobs)
            val recording = history.begin(HistoryRetention.FOREVER)
            recording.append(shortArrayOf(123, -456, 789)); recording.finish(false)
            val original = history.get(recording.entry.id)
            val bytes = original.part(0).readBytes()
            history.selectForExport(original.id).use { selected ->
                val request = AudioExportRequest(selected.name, selected.mimeType, false)
                val intent = AudioExportContract().createIntent(app, request)
                assertEquals("audio/wav", intent.type)
                assertEquals(selected.name, intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
                history.planImportEncoding(original.id, AudioQuality(compress = true), jobs.currentEpoch())
                val job = jobs.claim()!!
                AudioMetadata.write(File(original.directory, "capture.properties"), Properties().apply {
                    setProperty("frames", original.samples.toString()); setProperty("closed", "true")
                })
                val encoded = File(original.directory, job.targetName).apply { writeBytes(byteArrayOf(7, 8, 9)) }
                jobs.target(job.id, job.generation, encoded.toURI().toString()); jobs.ready(job.id, job.generation)
                assertTrue(history.publishArchive(job))
                assertEquals("audio/mp4", history.get(original.id).asset(0).mimeType)
                val destination = File(root, selected.name)
                DialogAudioActions(app).export(selected, Uri.fromFile(destination), false)
                assertArrayEquals(bytes, destination.readBytes())
            }
        } finally { jobs.close(); root.deleteRecursively() }
    }
}
