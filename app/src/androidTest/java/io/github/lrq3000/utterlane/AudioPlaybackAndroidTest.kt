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

    private fun fixture(): HistoryEntry {
        val audio = app.recordingHistory.begin(HistoryRetention.NONE)
        repeat(20) { audio.append(ShortArray(3200)) } // Four seconds; no audible test tone.
        audio.finish(true)
        return app.recordingHistory.get(audio.entry.id)
    }

    @Test fun dialogExpandsPlaybackAndSeeksWhilePausedThenCollapsesOnStop() = runBlocking {
        ui.prepare()
        val entry = fixture()
        val activity = instrumentation.startActivitySync(RecordingRecovery.intent(app, entry.id))
        try {
            ui.click("audio_play")
            val playing = withTimeout(10000) { app.audioPlayback.state.first { it.playing } }
            assertTrue(playing.durationMs >= 3900)
            ui.node("audio_seek").recycle()
            ui.click("audio_pause")
            withTimeout(5000) { app.audioPlayback.state.first { it.active && !it.playing } }
            instrumentation.runOnMainSync { app.audioPlayback.seek(playing.owner!!, 2500) }
            val sought = withTimeout(5000) { app.audioPlayback.state.first { !it.preparing && kotlin.math.abs(it.positionMs - 2500) < 300 } }
            assertFalse(sought.playing)
            ui.screenshot("transcription-audio-paused-seek")
            ui.click("audio_pause") // Same button is now Resume.
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
}
