package io.github.lrq3000.utterlane

import android.app.Activity
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeJournal
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Provider rejection must not acknowledge an earlier owned Home workspace. */
@RunWith(AndroidJUnit4::class)
class HomeFileReplacementAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun missingAndEmptyFilesPreservePriorOwnerAudioTextAndJournal() = runBlocking<Unit> {
        assertTrue("Use the parent-owned isolated QA identity", app.packageName.endsWith(".dhome"))
        ui.prepare()
        val onboarding = OnboardingRepository(app)
        val previous = onboarding.progress.first()
        val validCopy = File.createTempFile("home-prior-", ".wav", app.cacheDir).apply {
            // Nonempty but deliberately undecodable: this is usable owned input
            // for retry regardless of whether the QA device has ASR weights.
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val empty = File.createTempFile("home-empty-", ".wav", app.cacheDir)
        val missing = File(app.cacheDir, "home-missing-${UUID.randomUUID()}.wav")
        var home: Activity? = null
        var audioId: String? = null
        try {
            onboarding.complete()
            home = instrumentation.startActivitySync(HomeActivity.intent(app))
            ui.node("home_screen").recycle()
            assertNull("Do not replace another test/user's result", app.homeController.state.value.model)
            instrumentation.runOnMainSync { app.homeController.load(Uri.fromFile(validCopy)) }
            val prior = withTimeout(120000) { app.homeController.state.first {
                it.model != null && !it.busy && it.result.message != null
            } }
            instrumentation.runOnMainSync { } // Let the result observer checkpoint finish.
            val audio = checkNotNull(prior.result.audio)
            audioId = audio.id
            assertTrue(audio.temporary)
            val audioBytes = audio.part(0).readBytes()
            val textBytes = prior.result.store?.file?.readBytes()
            val descriptor = HomeJournal(app).restore()
            val historyIds = app.recordingHistory.list(0, 1000).map { it.id }
            assertArrayEquals(validCopy.readBytes(), audioBytes)

            // Repetition exercises retirement of failed owners and permits the
            // next selection only after its preparation/cleanup hold is released.
            for (file in listOf(missing, empty, missing, empty)) {
                instrumentation.runOnMainSync {
                    app.homeController.load(Uri.fromFile(file))
                    assertTrue(app.homeController.state.value.preparing)
                    assertSame(prior.model, app.homeController.state.value.model)
                    assertEquals(descriptor, HomeJournal(app).restore())
                }
                val failed = withTimeout(30000) { app.homeController.state.first {
                    !it.busy && it.message != null
                } }
                instrumentation.runOnMainSync { }
                assertSame("A failed selection must retain the prior model", prior.model, failed.model)
                assertFalse(checkNotNull(prior.model).state.value.closing)
                assertEquals(audio.id, failed.result.audio?.id)
                assertSame(prior.result.store, failed.result.store)
                assertEquals(prior.result.preview, failed.result.preview)
                assertArrayEquals(audioBytes, audio.part(0).readBytes())
                if (textBytes != null) assertArrayEquals(textBytes, checkNotNull(failed.result.store).file.readBytes())
                assertEquals(descriptor, HomeJournal(app).restore())
                assertEquals(historyIds, app.recordingHistory.list(0, 1000).map { it.id })
                assertFalse(failed.permissionDenied)
            }
            instrumentation.runOnMainSync { app.homeController.dismiss() }
            withTimeout(10000) { app.homeController.state.first { it.model == null && !it.busy } }
        } finally {
            instrumentation.runOnMainSync { app.homeController.dismiss(); home?.finish() }
            audioId?.let(app.recordingHistory::delete)
            validCopy.delete(); empty.delete()
            onboarding.update { previous }
        }
    }
}
