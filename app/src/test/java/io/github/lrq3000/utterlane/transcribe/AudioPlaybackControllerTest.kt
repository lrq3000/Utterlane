package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.HistoryEntry
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** Real controller/history with a manually completed native prepare, so races are deterministic. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 36])
class AudioPlaybackControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var history: RecordingHistory
    private lateinit var scope: CoroutineScope
    private lateinit var controller: AudioPlaybackController
    private val players = mutableListOf<MediaPlayer>()
    private val native get() = shadowOf(players.last())

    @Before fun setUp() {
        history = RecordingHistory(temporary.newFolder("history"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val app = mockk<UtterlaneApp>(relaxed = true)
        every { app.applicationScope } returns scope
        every { app.recordingHistory } returns history
        every { app.getSystemService(AudioManager::class.java) } returns
            RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        controller = AudioPlaybackController(app)
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
    }

    @After fun tearDown() {
        controller.state.value.owner?.let(controller::stop)
        scope.cancel()
        ShadowMediaPlayer.resetStaticState()
    }

    private fun fixture(): HistoryEntry {
        val recording = history.begin(HistoryRetention.NONE)
        recording.append(ShortArray(64000))
        recording.finish(true)
        return history.get(recording.entry.id).also {
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(it.part(0).absolutePath),
                ShadowMediaPlayer.MediaInfo(4000, -1))
        }
    }

    private fun awaitingNativePrepare(entry: HistoryEntry = fixture(), owner: String = "owner") {
        val count = players.size
        controller.play(owner, entry.id)
        await { players.size > count }
        assertTrue(controller.state.value.preparing)
        assertFalse(controller.state.value.playing)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue("Timed out awaiting playback state: ${controller.state.value}", condition())
    }

    @Test fun ownerPauseAlreadyCancelsAutoplayDuringNativePreparation() {
        awaitingNativePrepare()
        controller.pause("owner")
        native.invokePreparedListener()
        assertTrue(controller.state.value.active)
        assertFalse(controller.state.value.playing)
        assertFalse(players.last().isPlaying)
    }

    @Test fun capturePauseCancelsAutoplayBeforeHistoryLookupRuns() {
        val entry = fixture()
        controller.play("owner", entry.id)
        controller.pauseForCapture()
        await { players.isNotEmpty() }
        native.invokePreparedListener()
        assertTrue(controller.state.value.active)
        assertFalse("A late prepare must not start audio during capture", controller.state.value.playing)
        assertFalse(players.last().isPlaying)
    }

    @Test fun capturePauseCancelsAutoplayDuringNativePreparationUntilExplicitResume() {
        awaitingNativePrepare()
        controller.pauseForCapture()
        native.invokePreparedListener()
        assertFalse("A late prepare must not start audio during capture", players.last().isPlaying)
        controller.play("owner", controller.state.value.audioId!!)
        assertTrue(players.last().isPlaying)
    }

    @Test fun capturePauseRetainsPositionOwnerAndLease() {
        val entry = fixture()
        awaitingNativePrepare(entry)
        native.invokePreparedListener()
        native.setCurrentPosition(1250)
        controller.pauseForCapture()
        controller.pauseForCapture() // Admission cleanup may repeat without losing position.
        assertFalse(players.last().isPlaying)
        assertEquals("owner", controller.state.value.owner)
        assertEquals(1250L, controller.state.value.positionMs)
        assertTrue(controller.state.value.active)
        history.delete(entry.id)
        assertTrue("Paused audio keeps its source lease", entry.directory.exists())
        controller.stop("owner")
        await { !entry.directory.exists() }
    }

    @Test fun capturePauseWinsOverPendingSeekCompletion() {
        awaitingNativePrepare()
        native.invokePreparedListener()
        native.seekDelay = -1
        controller.seek("owner", 2500)
        controller.pauseForCapture()
        native.invokeSeekCompleteListener()
        assertFalse(players.last().isPlaying)
        assertEquals(2500L, controller.state.value.positionMs)
        assertFalse(controller.state.value.preparing)
    }
}
