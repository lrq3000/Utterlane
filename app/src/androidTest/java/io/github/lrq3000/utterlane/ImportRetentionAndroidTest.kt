package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeJournal
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.transcribe.*
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real source inspection, activities, shared model, preferences and repository.
 * Use an isolated model-free QA installation; no recognition fixture is needed. */
@RunWith(AndroidJUnit4::class)
class ImportRetentionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    private suspend fun configured(action: suspend () -> Unit) {
        assertFalse("Use a model-free QA installation", app.modelManager.isModelReady())
        val settings = app.settingsRepository
        val before = settings.importRetentionPreferences.first()
        val retention = settings.audioHistoryRetention.first()
        try {
            settings.setAudioHistoryEnabled(true)
            settings.setAudioHistoryRetention(HistoryRetention.HOUR)
            settings.resetImportRetentionChoices()
            ui.prepare()
            action()
        } finally {
            settings.resetImportRetentionChoices()
            before.durationChoice?.let { settings.rememberImportRetention(ImportRetentionCategory.DURATION, it) }
            before.sizeChoice?.let { settings.rememberImportRetention(ImportRetentionCategory.SIZE, it) }
            settings.setAudioHistoryEnabled(before.audioHistoryEnabled)
            settings.setAudioHistoryRetention(retention)
        }
    }

    private fun largeUnknown() = File.createTempFile("import-size-", ".mp3", app.cacheDir).apply {
        RandomAccessFile(this, "rw").use { it.setLength(ImportRetentionPolicy.SIZE_THRESHOLD_BYTES + 1) }
    }

    private fun longPcm() = File.createTempFile("import-duration-", ".mp3", app.cacheDir).apply {
        // The deliberately misleading suffix must not classify PCM as compressed.
        WavFile(this).use { wav -> repeat(3005) { wav.append(ShortArray(3200)) } }
    }

    private fun model(screen: Activity): TranscriptionDialogModel {
        lateinit var result: TranscriptionDialogModel
        instrumentation.runOnMainSync { result = ViewModelProvider(screen as ViewModelStoreOwner)[TranscriptionDialogModel::class.java] }
        return result
    }

    @Test fun externalOpenAsksBeforeCopyAndShareUsesRememberedSizeChoice() = runBlocking {
        configured {
            val source = largeUnknown()
            val initial = app.recordingHistory.list(0, 10000).map { it.id }.toSet()
            var screen: Activity? = null
            try {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
                screen = launchNativeActivity(Intent(app, TranscribeActivity::class.java)
                    .setAction(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mpeg")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                ui.node("import_retention_question").recycle()
                ui.textNode("Remember my choice and do not ask again").recycle()
                capture("import-external-size-question")
                val first = model(checkNotNull(screen))
                assertNull(first.state.value.audio)
                assertEquals(initial, app.recordingHistory.list(0, 10000).map { it.id }.toSet())
                ui.click("import_remember")
                ui.click("import_transcript_only")
                val failed = withTimeout(30000) { first.state.first { !it.importing && !it.running && it.audio != null && it.message != null } }
                assertEquals(ImportRetentionChoice.DISCARD, app.settingsRepository.importRetentionPreferences.first().sizeChoice)
                assertNull(app.settingsRepository.importRetentionPreferences.first().durationChoice)
                assertTrue(failed.audio!!.temporary)
                assertTrue(failed.audio!!.needsRecovery)
                assertArrayEquals(digest(source), digest(failed.audio!!.part(0)))
                // Actual failure still asks before losing input, despite the import choice.
                ui.click("dialog_back"); ui.node("exit_confirmation").recycle(); ui.click("exit_back")
                val retired = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync { first.dismiss { retired.complete(Unit) }; screen!!.finish() }
                withTimeout(10000) { retired.await() }

                screen = launchNativeActivity(Intent(app, TranscribeActivity::class.java)
                    .setAction(Intent.ACTION_SEND).setType("audio/mpeg").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                val shared = model(checkNotNull(screen))
                val imported = withTimeout(30000) { shared.state.first { !it.importing && !it.running && it.audio != null } }
                assertNull(imported.importQuestion)
                assertTrue(imported.audio!!.temporary)
                assertEquals(source.length(), imported.audio!!.importMetadata!!.sourceBytes)
                assertNull(imported.audio!!.importMetadata!!.isCompressed)
                assertArrayEquals(digest(source), digest(imported.audio!!.part(0)))
                assertEquals(ImportRetentionPolicy.SIZE_THRESHOLD_BYTES + 1, source.length())
                val closed = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync { shared.dismiss { closed.complete(Unit) } }
                withTimeout(10000) { closed.await() }
            } finally {
                instrumentation.runOnMainSync { screen?.finish() }
                app.recordingHistory.list(0, 10000).filter { it.id !in initial }.forEach { app.recordingHistory.delete(it.id) }
                source.delete()
            }
        }
    }

    @Test fun homePendingQuestionCanCancelWithoutReplacingPriorThenKeepAndRemember() = runBlocking {
        configured {
            assertTrue("Use the isolated Home QA identity", app.packageName.endsWith(".dhome"))
            val onboarding = OnboardingRepository(app)
            val before = onboarding.progress.first()
            val priorSource = File.createTempFile("import-prior-", ".wav", app.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val source = longPcm()
            val initial = app.recordingHistory.list(0, 10000).map { it.id }.toSet()
            var screen: Activity? = null
            try {
                onboarding.complete()
                screen = launchNativeActivity(HomeActivity.intent(app))
                ui.node("home_screen").recycle()
                assertNull(app.homeController.state.value.model)
                instrumentation.runOnMainSync { app.homeController.load(Uri.fromFile(priorSource)) }
                val prior = withTimeout(30000) { app.homeController.state.first { !it.busy && it.model != null && it.result.message != null } }
                instrumentation.runOnMainSync { }
                val journal = HomeJournal(app).restore()
                instrumentation.runOnMainSync { app.homeController.load(Uri.fromFile(source)) }
                ui.node("import_retention_question").recycle()
                assertTrue(app.homeController.state.value.preparing)
                assertSame(prior.model, app.homeController.state.value.model)
                assertEquals(journal, HomeJournal(app).restore())
                capture("import-home-pending-duration-question")
                ui.click("import_cancel")
                withTimeout(10000) { app.homeController.state.first { !it.busy } }
                assertSame(prior.model, app.homeController.state.value.model)
                assertFalse(prior.model!!.state.value.closing)
                assertEquals(journal, HomeJournal(app).restore())

                instrumentation.runOnMainSync { app.homeController.load(Uri.fromFile(source)) }
                ui.node("import_retention_question").recycle()
                ui.click("import_remember"); ui.click("import_keep_audio")
                val next = withTimeout(30000) { app.homeController.state.first { !it.busy && it.model !== prior.model && it.result.message != null } }
                val audio = checkNotNull(next.result.audio)
                assertFalse(audio.temporary); assertFalse(audio.pinned)
                assertEquals(false, audio.importMetadata!!.isCompressed)
                assertEquals("audio/raw", audio.importMetadata!!.trackMimeType)
                assertTrue(audio.importMetadata!!.durationMs!! > 600000)
                assertEquals(ImportRetentionChoice.KEEP, app.settingsRepository.importRetentionPreferences.first().durationChoice)
                assertNull(app.settingsRepository.importRetentionPreferences.first().sizeChoice)
                assertTrue(prior.result.audio!!.part(0).exists())
                assertArrayEquals(digest(source), digest(audio.part(0)))
                capture("import-home-retained-result")

                instrumentation.runOnMainSync { app.homeController.load(Uri.fromFile(source)) }
                val remembered = withTimeout(30000) { app.homeController.state.first { !it.busy && it.model !== next.model && it.result.message != null } }
                assertFalse(remembered.result.audio!!.temporary)
                assertNull(remembered.result.importQuestion)
                assertTrue(source.exists())
                instrumentation.runOnMainSync { app.homeController.dismiss() }
                withTimeout(10000) { app.homeController.state.first { it.model == null && !it.busy } }
                assertTrue(audio.part(0).exists())
            } finally {
                instrumentation.runOnMainSync { app.homeController.dismiss(); screen?.finish() }
                app.recordingHistory.list(0, 10000).filter { it.id !in initial }.forEach { app.recordingHistory.delete(it.id) }
                priorSource.delete(); source.delete(); onboarding.update { before }
            }
        }
    }

    private fun capture(name: String) {
        // UI assertions above establish readiness; screenshots do not need the
        // instrumentation-global idle wait that the launch regression diagnoses.
        val directory = File(app.getExternalFilesDir(null), "import-retention-qa").apply { mkdirs() }
        File(directory, "$name.xml").outputStream().use { output ->
            val xml = android.util.Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true); startTag(null, "hierarchy") }
            fun node(value: android.view.accessibility.AccessibilityNodeInfo) {
                xml.startTag(null, "node")
                xml.attribute(null, "text", value.text?.toString().orEmpty())
                xml.attribute(null, "resource-id", value.viewIdResourceName.orEmpty())
                xml.attribute(null, "bounds", android.graphics.Rect().also(value::getBoundsInScreen).toShortString())
                for (index in 0 until value.childCount) value.getChild(index)?.let { child ->
                    try { node(child) } finally { @Suppress("DEPRECATION") child.recycle() }
                }
                xml.endTag(null, "node")
            }
            instrumentation.uiAutomation.windows.mapNotNull { it.root }.forEach { root ->
                try { if (root.packageName?.toString() == app.packageName) node(root) }
                finally { @Suppress("DEPRECATION") root.recycle() }
            }
            xml.endTag(null, "hierarchy"); xml.endDocument()
        }
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }

    private fun digest(file: File): ByteArray {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest()
    }
}
