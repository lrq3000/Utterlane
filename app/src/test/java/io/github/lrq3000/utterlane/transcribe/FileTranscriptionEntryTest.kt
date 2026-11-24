package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FileTranscriptionEntryTest {
    @Test fun shareAndOpenEnterSetupInsteadOfStartingRecognition() {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://test.audio/meeting.wav")
        val requests = listOf(
            Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/wav")
        )
        for (request in requests) {
            val handlers = app.packageManager.queryIntentActivities(request.setPackage(app.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(listOf("FileTranscriptionSetupActivity"), handlers.map { it.activityInfo.name.substringAfterLast('.') })
        }
    }

    @Test fun recoveryAndResultsAreNotExportedEntrypoints() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(android.content.ComponentName(app, TranscribeActivity::class.java), 0)
        assertFalse(info.exported)
    }

    @Test fun explicitOffSurvivesTheHandoffRatherThanInheritingSettings() {
        assertNull(FileTranscriptionOptions.from(Intent()))
        assertEquals(false, FileTranscriptionOptions.from(FileTranscriptionOptions().putInto(Intent()))?.speakerLabels)
        assertEquals(true, FileTranscriptionOptions.from(FileTranscriptionOptions(true).putInto(Intent()))?.speakerLabels)
    }
}
