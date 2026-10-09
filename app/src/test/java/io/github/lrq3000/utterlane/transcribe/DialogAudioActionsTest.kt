package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import android.content.ContentResolver
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

        val thrown = assertThrows(Exception::class.java) { DialogAudioActions(app).export(entry.id, directory, true) }
        assertSame(failure, thrown)
        assertTrue(thrown.suppressed.contains(cleanupFailure))
        assertArrayEquals(byteArrayOf(1, 2, 3), entry.part(0).readBytes())
    }
}
