package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Looper
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.HistoryEntry
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.settings.VisualRefreshRate
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.util.Properties

/** Real controller/history with a manually completed native prepare, so races are deterministic. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 36], shadows = [SpeedMediaPlayer::class])
class AudioPlaybackControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var history: RecordingHistory
    private lateinit var scope: CoroutineScope
    private lateinit var controller: AudioPlaybackController
    private val players = mutableListOf<MediaPlayer>()
    private val native get() = Shadow.extract<SpeedMediaPlayer>(players.last())

    @Before fun setUp() {
        history = RecordingHistory(temporary.newFolder("history"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val app = mockk<UtterlaneApp>(relaxed = true)
        every { app.applicationScope } returns scope
        every { app.recordingHistory } answers { history }
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

    private fun fixture(multipart: Boolean = false): HistoryEntry {
        val recording = history.begin(HistoryRetention.NONE)
        recording.append(ShortArray(64000))
        recording.finish(true)
        if (multipart) {
            // Model an hour boundary without writing an hour of PCM. Native audio
            // is shadowed; the real history still manages the entry and its lease.
            val metadata = recording.entry.directory.resolve("recording.properties")
            val properties = Properties().apply { metadata.inputStream().use(::load) }
            properties.setProperty("samples", (RecordingHistory.PART_SAMPLES + 64000).toString())
            metadata.outputStream().use { properties.store(it, null) }
            history = RecordingHistory(recording.entry.directory.parentFile).also { it.initialize() }
        }
        return history.get(recording.entry.id).also {
            for (part in 0 until it.parts) {
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(it.part(part).absolutePath),
                    ShadowMediaPlayer.MediaInfo(if (multipart && part == 0) 3600000 else 4000, -1))
            }
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

    private fun speed(value: Float, owner: String = "owner") {
        controller.setPlaybackSpeed(owner, value)
    }

    @Test fun pausedSpeedIsDeferredWithoutNativeAutostartUntilExplicitResume() {
        awaitingNativePrepare()
        native.invokePreparedListener()
        controller.pause("owner")
        native.setCurrentPosition(1500)
        val changes = native.speedChanges
        speed(1.5f)
        assertFalse(players.last().isPlaying)
        assertEquals("Do not send params to a paused MediaPlayer", changes, native.speedChanges)
        assertEquals(1500, players.last().currentPosition)
        controller.play("owner", controller.state.value.audioId!!)
        assertEquals(1.5f, players.last().playbackParams.speed)
    }

    @Test fun speedDuringPreparationAndCapturePauseWaitsForExplicitResume() {
        awaitingNativePrepare()
        speed(0.75f)
        controller.pauseForCapture()
        native.invokePreparedListener()
        assertFalse(players.last().isPlaying)
        assertEquals(0, native.speedChanges)
        controller.play("owner", controller.state.value.audioId!!)
        assertEquals(0.75f, players.last().playbackParams.speed)
    }

    @Test fun speedDuringSeekDoesNotRestartAtAnObsoletePosition() {
        awaitingNativePrepare()
        native.invokePreparedListener()
        native.seekDelay = -1
        controller.seek("owner", 2500)
        val changes = native.speedChanges
        speed(1.75f)
        assertEquals(changes, native.speedChanges)
        assertFalse(players.last().isPlaying)
        native.invokeSeekCompleteListener()
        assertTrue(players.last().isPlaying)
        assertEquals(1.75f, players.last().playbackParams.speed)
    }

    @Test fun allMenuSpeedsApplyLiveIndependentlyOfVisualRefreshCadence() {
        awaitingNativePrepare()
        native.invokePreparedListener()
        for (value in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
            speed(value)
            controller.setRate("owner", VisualRefreshRate.DEFAULT)
            assertEquals(value, players.last().playbackParams.speed)
            assertTrue(players.last().isPlaying)
        }
    }

    @Test fun requestedSpeedSurvivesPartTransitionStopAndNewOwnerButRejectsStaleOwner() {
        val entry = fixture(multipart = true)
        awaitingNativePrepare(entry)
        native.invokePreparedListener()
        speed(1.25f)
        native.invokeCompletionListener()
        assertEquals(2, players.size)
        native.invokePreparedListener()
        assertEquals(1.25f, players.last().playbackParams.speed)
        controller.stop("owner")
        awaitingNativePrepare(owner = "new-owner")
        speed(2f) // Old UI cannot change the new owner's speed.
        native.invokePreparedListener()
        assertEquals(1.25f, players.last().playbackParams.speed)
    }

    @Test fun rejectedSpeedKeepsPlaybackAndReportsObservedRateInsteadOfRequestedRate() {
        awaitingNativePrepare()
        native.invokePreparedListener()
        native.rejectSpeed = true
        speed(2f)
        assertTrue(players.last().isPlaying)
        assertEquals(1f, players.last().playbackParams.speed)
        assertEquals(2f, controller.state.value.requestedSpeed)
        assertEquals(1f, controller.state.value.appliedSpeed)
        assertTrue(controller.state.value.speedUnavailable)
    }

    @Test fun silentNativeFallbackIsReportedAndUnknownReadbackDoesNotInventSpeed() {
        awaitingNativePrepare()
        native.invokePreparedListener()
        native.forcedSpeed = 1f
        speed(1.75f)
        assertEquals(1f, controller.state.value.appliedSpeed)
        assertTrue(controller.state.value.speedUnavailable)
        native.rejectReadback = true
        speed(2f)
        assertNull(controller.state.value.appliedSpeed)
        assertTrue(controller.state.value.speedUnavailable)
        assertTrue(players.last().isPlaying)
    }

    @Test fun nextPartRetriesTheRequestedSpeedAfterNativeRejection() {
        awaitingNativePrepare(fixture(multipart = true))
        native.invokePreparedListener()
        native.rejectSpeed = true
        speed(1.5f)
        native.invokeCompletionListener()
        assertNull(controller.state.value.appliedSpeed)
        native.invokePreparedListener()
        assertEquals(1.5f, players.last().playbackParams.speed)
        assertFalse(controller.state.value.speedUnavailable)
    }
}

/** Robolectric lacks playback params. Model Android's documented implicit start
 * and native rejection here; the controller and other MediaPlayer states are real. */
@Implements(MediaPlayer::class)
class SpeedMediaPlayer : ShadowMediaPlayer() {
    private var params = PlaybackParams().allowDefaults()
    var speedChanges = 0
        private set
    var rejectSpeed = false
    var rejectReadback = false
    var forcedSpeed: Float? = null

    @Implementation fun getPlaybackParams(): PlaybackParams {
        check(!rejectReadback) { "Native speed unavailable" }
        return PlaybackParams().allowDefaults().setSpeed(params.speed)
    }

    @Implementation fun setPlaybackParams(value: PlaybackParams) {
        speedChanges++
        require(!rejectSpeed) { "Native speed rejected" }
        params = PlaybackParams().allowDefaults().setSpeed(forcedSpeed ?: value.speed)
        start() // Crucial: an accidental setter on a paused player becomes audible.
    }
}
