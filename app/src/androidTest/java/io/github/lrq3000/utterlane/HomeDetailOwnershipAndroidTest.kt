package io.github.lrq3000.utterlane

import android.app.Activity
import android.net.Uri
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeJournal
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A detail overlay borrows Home's owner; only explicit Dismiss ends that work. */
@RunWith(AndroidJUnit4::class)
class HomeDetailOwnershipAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun detailArrowAndSystemBackRetainTheOwnedTemporaryWorkspace() = runBlocking<Unit> {
        assertTrue("Use an isolated QA install", app.packageName.endsWith(".dhome") || app.packageName.endsWith(".polishqa") ||
            app.packageName.endsWith(".audiorecorderports"))
        ui.prepare()
        val onboarding = OnboardingRepository(app)
        val previous = onboarding.progress.first()
        val original = File.createTempFile("home-detail-", ".wav", app.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        var home: Activity? = null
        var audioId: String? = null
        try {
            onboarding.complete()
            home = instrumentation.startActivitySync(HomeActivity.intent(app))
            ui.node("home_screen").recycle()
            assertNull("Do not replace another test/user's workspace", app.homeController.state.value.model)
            instrumentation.runOnMainSync { app.homeController.load(Uri.fromFile(original)) }
            // No ASR weights are required: either model preparation or decoding
            // this deliberately invalid file fails after its private copy is owned.
            val result = withTimeout(120000) { app.homeController.state.first {
                it.model != null && !it.busy && it.result.message != null
            } }
            val owner = checkNotNull(result.model)
            val audio = checkNotNull(result.result.audio)
            audioId = audio.id
            assertTrue(audio.temporary)
            assertNotEquals(original.canonicalPath, audio.part(0).canonicalPath)
            // Home publishes UI state just before checkpointing in the same
            // observer turn. Compare journals only after that turn completes.
            instrumentation.runOnMainSync { }
            val descriptor = HomeJournal(app).restore()
            for (arrow in listOf(true, false)) {
                ui.scrollTo("home_more")
                ui.click("home_more")
                ui.clickText(app.getString(R.string.home_details))
                ui.node("transcription_dialog").recycle()
                if (arrow) ui.click("dialog_back")
                else instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                ui.node("home_screen").recycle()
                assertSame("Closing the borrowed detail must retain Home's model", owner, app.homeController.state.value.model)
                assertFalse("Navigation must not start asynchronous dismissal", owner.state.value.closing)
                assertEquals(audio.id, app.homeController.state.value.result.audio?.id)
                assertTrue(app.recordingHistory.get(audio.id).temporary)
                assertTrue(audio.part(0).isFile)
                assertArrayEquals(original.readBytes(), audio.part(0).readBytes())
                assertEquals(descriptor, HomeJournal(app).restore())
            }
            // The explicit action still owns cleanup, unlike either Back control.
            ui.click("home_more")
            ui.clickText(app.getString(R.string.home_dismiss))
            withTimeout(10000) { app.homeController.state.first { it.model == null && !it.busy } }
        } finally {
            instrumentation.runOnMainSync { app.homeController.dismiss(); home?.finish() }
            audioId?.let(app.recordingHistory::delete)
            original.delete()
            onboarding.update { previous }
        }
    }
}
