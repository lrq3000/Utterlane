package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DialogAudioActionsTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun tearDown() = unmockkAll()

    @Test fun retentionInspectionUsesTheSameUsableTrackAsTheDecoder() {
        val app = mockk<UtterlaneApp>()
        val resolver = mockk<ContentResolver>(relaxed = true)
        every { app.contentResolver } returns resolver
        val source = Uri.parse("content://source/document/multitrack")
        every { resolver.getType(source) } returns "audio/mp4"
        mockkConstructor(android.media.MediaExtractor::class)
        every { anyConstructed<android.media.MediaExtractor>().setDataSource(app, source, null) } just Runs
        every { anyConstructed<android.media.MediaExtractor>().trackCount } returns 2
        every { anyConstructed<android.media.MediaExtractor>().getTrackFormat(0) } returns
            android.media.MediaFormat.createAudioFormat("audio/raw", 0, 1)
        every { anyConstructed<android.media.MediaExtractor>().getTrackFormat(1) } returns
            android.media.MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2).apply {
                setLong(android.media.MediaFormat.KEY_DURATION, 601_000_000)
            }
        every { anyConstructed<android.media.MediaExtractor>().release() } just Runs
        val metadata = DialogAudioActions(app).inspect(source, null).metadata
        assertEquals("audio/mp4a-latm", metadata.trackMimeType)
        assertEquals(true, metadata.isCompressed)
        assertEquals(601_000L, metadata.durationMs)
        verify(exactly = 0) { resolver.openInputStream(any()) }
    }

    @Test fun providerImportDoesNotRequireAKnownSizeAndPreservesOriginalBytes() {
        val app = mockk<UtterlaneApp>()
        val resolver = mockk<ContentResolver>()
        val history = RecordingHistory(temporary.newFolder())
        val source = Uri.parse("content://source/document/audio")
        val bytes = byteArrayOf(4, 5, 6)
        every { app.recordingHistory } returns history
        every { app.contentResolver } returns resolver
        every { resolver.query(source, any(), null, null, null) } answers {
            MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("audio.opus")) }
        }
        every { resolver.getType(source) } returns "audio/ogg"
        every { resolver.openInputStream(source) } answers { bytes.inputStream() }
        val actions = DialogAudioActions(app)
        val inspected = actions.inspect(source, null)
        assertNull(inspected.metadata.sourceBytes)
        val imported = actions.import(inspected, io.github.lrq3000.utterlane.history.ImportRetentionChoice.KEEP)
        imported.lease.use {
            assertArrayEquals(bytes, imported.entry.part(0).readBytes())
            assertEquals("ready", imported.entry.status)
        }
    }

    @Test fun failedExportCleanupCannotHideTheOriginalStorageError() {
        val app = mockk<UtterlaneApp>()
        val resolver = mockk<ContentResolver>()
        val history = RecordingHistory(temporary.newFolder())
        val entry = history.importAudio(byteArrayOf(1, 2, 3).inputStream(), "wav", "audio/wav")
        every { app.recordingHistory } returns history
        every { app.contentResolver } returns resolver
        val directory = Uri.parse("content://destination/tree/folder")
        val target = Uri.parse("content://destination/document/new")
        val parent = mockk<DocumentFile>()
        val child = mockk<DocumentFile>()
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(app, directory) } returns parent
        every { parent.createFile(any(), any()) } returns child
        every { child.uri } returns target
        every { DocumentFile.fromSingleUri(app, target) } returns child
        val failure = IOException("Provider write failed", ErrnoException("write", OsConstants.ENOSPC))
        val cleanupFailure = SecurityException("Provider revoked access during cleanup")
        every { child.delete() } throws cleanupFailure
        every { resolver.openOutputStream(target, "wt") } returns object : OutputStream() {
            override fun write(value: Int) { throw failure }
        }

        val thrown = history.selectForExport(entry.id).use { selection ->
            assertThrows(Exception::class.java) { DialogAudioActions(app).export(selection, directory, true) }
        }
        assertSame(failure, thrown)
        assertTrue(thrown.suppressed.contains(cleanupFailure))
        assertArrayEquals(byteArrayOf(1, 2, 3), entry.part(0).readBytes())
    }
}
