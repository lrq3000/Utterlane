package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPlaybackAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    private fun fixture(seconds: Int = 4): HistoryEntry {
        val audio = app.recordingHistory.begin(HistoryRetention.NONE)
        repeat(seconds * 5) { audio.append(ShortArray(3200)) } // No audible test tone.
        audio.finish(true)
        return app.recordingHistory.get(audio.entry.id)
    }

    private fun assertNativePaused() = instrumentation.runOnMainSync {
        // The UI state alone cannot catch playbackParams implicitly starting a
        // paused player behind the controller's back. Inspect the real native state.
        val field = app.audioPlayback.javaClass.getDeclaredField("player").apply { isAccessible = true }
        val native = field.get(app.audioPlayback) as android.media.MediaPlayer
        assertFalse("Native audio must remain paused", native.isPlaying)
    }

    @Test fun preparedDurationOverridesAnOverestimatedImportedDuration() = runBlocking {
        val raw = fixture()
        val imported = raw.part(0).inputStream().use { app.recordingHistory.importAudio(it, "wav", "audio/wav", 9000) }
        app.recordingHistory.delete(raw.id)
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, imported.id))
        try {
            instrumentation.runOnMainSync { app.audioPlayback.play("duration-check", imported.id) }
            val ready = withTimeout(10000) { app.audioPlayback.state.first { it.playing } }
            assertTrue("Seek timeline must use the prepared audio duration, not the import estimate: ${ready.durationMs}", ready.durationMs in 3900..4100)
        } finally {
            instrumentation.runOnMainSync { app.audioPlayback.stop("duration-check"); activity.finish() }
            app.recordingHistory.delete(imported.id)
        }
    }

    @Test fun dialogExpandsPlaybackAndSeeksWhilePausedThenCollapsesOnStop() = runBlocking {
        ui.prepare()
        // Accessibility traversal on a software-rendered emulator can exceed four
        // seconds. Keep the fixture alive until the explicit end-of-audio seek.
        val entry = fixture(seconds = 30)
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, entry.id))
        try {
            ui.click("audio_play")
            val playing = withTimeout(10000) { app.audioPlayback.state.first { it.playing } }
            assertTrue(playing.durationMs >= 29900)
            ui.node("audio_seek").recycle()
            ui.click("audio_pause")
            withTimeout(5000) { app.audioPlayback.state.first { it.active && !it.playing } }
            instrumentation.runOnMainSync { app.audioPlayback.seek(playing.owner!!, 2500) }
            val sought = withTimeout(5000) { app.audioPlayback.state.first { !it.preparing && kotlin.math.abs(it.positionMs - 2500) < 300 } }
            assertFalse(sought.playing)
            ui.descriptionNode(app.getString(R.string.audio_resume)).recycle()
            ui.textNode("0:02 / 0:30").recycle()
            ui.screenshot("transcription-audio-paused-seek")
            ui.click("audio_pause") // Same button is now Resume.
            withTimeout(5000) { app.audioPlayback.state.first { it.playing } }
            instrumentation.runOnMainSync { app.audioPlayback.seek(playing.owner!!, playing.durationMs - 100) }
            withTimeout(3000) { app.audioPlayback.state.first { !it.active } }
            ui.click("audio_play")
            withTimeout(5000) { app.audioPlayback.state.first { it.playing } }
            ui.click("audio_stop")
            assertFalse(app.audioPlayback.state.value.active)
            assertEquals(0L, app.audioPlayback.state.value.positionMs)
            ui.node("audio_play").recycle()
        } finally {
            instrumentation.runOnMainSync { app.audioPlayback.stopAudio(entry.id); activity.finish() }
            app.recordingHistory.delete(entry.id)
        }
    }

    @Test fun oldOwnerCannotStopNewPlaybackAndLeaseDefersPhysicalDeletion() = runBlocking {
        val entry = fixture()
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, entry.id))
        try {
            instrumentation.runOnMainSync {
                app.audioPlayback.play("old", entry.id)
                app.audioPlayback.play("new", entry.id)
            }
            withTimeout(10000) { app.audioPlayback.state.first { it.owner == "new" && it.playing } }
            instrumentation.runOnMainSync { app.audioPlayback.stop("old"); app.audioPlayback.pause("new") }
            assertTrue(app.audioPlayback.state.value.active)
            app.recordingHistory.delete(entry.id)
            assertTrue(entry.directory.exists())
            instrumentation.runOnMainSync { app.audioPlayback.stop("new") }
            withTimeout(5000) { while (entry.directory.exists()) delay(10) }
        } finally {
            instrumentation.runOnMainSync { app.audioPlayback.stopAudio(entry.id); activity.finish() }
            app.recordingHistory.delete(entry.id)
        }
    }

    @Test fun stopDuringPreparationCannotRestartOrLeakItsSourceLease() = runBlocking {
        val entry = fixture()
        instrumentation.runOnMainSync {
            app.audioPlayback.play("cancel-prepare", entry.id)
            app.audioPlayback.stop("cancel-prepare")
        }
        app.recordingHistory.delete(entry.id)
        withTimeout(5000) { while (entry.directory.exists()) delay(10) }
        assertFalse(app.audioPlayback.state.value.active)
    }

    @Test fun capturePauseBeforePrepareKeepsAudioSilentUntilExplicitResume() = runBlocking {
        val entry = fixture()
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, entry.id))
        try {
            instrumentation.runOnMainSync {
                app.audioPlayback.play("capture-prepare", entry.id)
                app.audioPlayback.setPlaybackSpeed("capture-prepare", 1.5f)
                // Same Main turn: neither IO lookup nor onPrepared can finish yet.
                app.audioPlayback.pauseForCapture()
            }
            val paused = withTimeout(10000) { app.audioPlayback.state.first { it.active && !it.preparing } }
            assertFalse(paused.playing)
            assertEquals(0L, paused.positionMs)
            assertNull("Deferred speed has not touched native playback", paused.appliedSpeed)
            assertNativePaused()
            instrumentation.runOnMainSync { app.audioPlayback.play("capture-prepare", entry.id) }
            val playing = withTimeout(5000) { app.audioPlayback.state.first { it.playing } }
            assertEquals(1.5f, playing.appliedSpeed)
            instrumentation.runOnMainSync { app.audioPlayback.pauseForCapture() }
            val position = app.audioPlayback.state.value.positionMs
            delay(300) // Negative assertion: no delayed callback or ticker resumes it.
            assertFalse(app.audioPlayback.state.value.playing)
            assertEquals(position, app.audioPlayback.state.value.positionMs)
        } finally {
            instrumentation.runOnMainSync {
                app.audioPlayback.setPlaybackSpeed("capture-prepare", 1f)
                app.audioPlayback.stopAudio(entry.id)
                activity.finish()
            }
            app.recordingHistory.delete(entry.id)
        }
    }

    @Test fun nativePlayerAcceptsAllPlaybackSpeeds() = runBlocking {
        val entry = fixture(seconds = 30)
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, entry.id))
        try {
            instrumentation.runOnMainSync { app.audioPlayback.play("speed-native", entry.id) }
            withTimeout(10000) { app.audioPlayback.state.first { it.playing } }
            for (speed in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
                instrumentation.runOnMainSync { app.audioPlayback.setPlaybackSpeed("speed-native", speed) }
                assertEquals(speed, app.audioPlayback.state.value.appliedSpeed)
                assertFalse(app.audioPlayback.state.value.speedUnavailable)
                assertTrue(app.audioPlayback.state.value.playing)
            }
        } finally {
            instrumentation.runOnMainSync {
                app.audioPlayback.setPlaybackSpeed("speed-native", 1f)
                app.audioPlayback.stopAudio(entry.id)
                activity.finish()
            }
            app.recordingHistory.delete(entry.id)
        }
    }

    @Test fun speedMenuKeepsPausedPositionAndAppliesSelectionOnResume() = runBlocking {
        ui.prepare()
        val entry = fixture(seconds = 30)
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, entry.id))
        var owner: String? = null
        try {
            ui.click("audio_play")
            owner = withTimeout(10000) { app.audioPlayback.state.first { it.playing } }.owner!!
            ui.click("audio_pause")
            withTimeout(5000) { app.audioPlayback.state.first { !it.playing } }
            instrumentation.runOnMainSync { app.audioPlayback.seek(checkNotNull(owner), 1500) }
            withTimeout(5000) { app.audioPlayback.state.first { !it.preparing } }
            val position = app.audioPlayback.state.value.positionMs
            ui.click("audio_speed")
            for (label in listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×")) {
                ui.textNode(label).recycle()
            }
            ui.clickText("1.5×")
            withTimeout(5000) { app.audioPlayback.state.first { it.requestedSpeed == 1.5f } }
            delay(300)
            assertFalse(app.audioPlayback.state.value.playing)
            assertEquals(position, app.audioPlayback.state.value.positionMs)
            assertNativePaused()
            ui.textNode(app.getString(R.string.audio_playback_speed_pending)).recycle()
            ui.screenshot("transcription-audio-paused-speed")
            ui.click("audio_pause") // Same control now means Resume.
            val resumed = withTimeout(5000) { app.audioPlayback.state.first { it.playing } }
            assertEquals(1.5f, resumed.appliedSpeed)
        } finally {
            instrumentation.runOnMainSync {
                owner?.let { app.audioPlayback.setPlaybackSpeed(it, 1f) }
                app.audioPlayback.stopAudio(entry.id)
                activity.finish()
            }
            app.recordingHistory.delete(entry.id)
        }
    }
}
