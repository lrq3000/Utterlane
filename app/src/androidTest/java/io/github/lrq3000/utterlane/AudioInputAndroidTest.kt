package io.github.lrq3000.utterlane

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.AudioRecorder
import io.github.lrq3000.utterlane.asr.CaptureObserver
import io.github.lrq3000.utterlane.audio.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Real phone capture/routing tests; these do not simulate a physical Bluetooth radio. */
@RunWith(AndroidJUnit4::class)
class AudioInputAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp

    private fun grantMicrophone() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test fun unadvertisedUnprocessedIsRejectedWithoutEmittingSubstitutedPcm() = runBlocking<Unit> {
        val manager = app.getSystemService(AudioManager::class.java)
        assumeTrue("This rejection check requires a device without advertised UNPROCESSED support",
            manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != "true")
        grantMicrophone()
        val saved = app.settingsRepository.audioInputPreferences.first()
        app.audioInputs.select(AudioInput.PHONE_KEY)
        val recorder = AudioRecorder().apply {
            configureMicrophone(MicrophoneOptions.HFP.copy(source = MicrophoneSource.UNPROCESSED))
        }
        val error = AtomicReference<Throwable>()
        val samples = AtomicInteger()
        val worker = Thread {
            val deadline = android.os.SystemClock.uptimeMillis() + 2000
            try { recorder.startRecording({ samples.addAndGet(it.size) }, { android.os.SystemClock.uptimeMillis() < deadline }) }
            catch (failure: Throwable) { error.set(failure) }
        }
        try {
            worker.start(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertNotNull("Unsupported UNPROCESSED must not silently use processed capture", error.get())
            assertEquals(0, samples.get())
        } finally {
            recorder.stop(); worker.join(3000)
            app.settingsRepository.updateAudioInput { saved }
        }
    }

    @Test fun phoneCaptureReportsActualInputAndStopReleasesTheWorker() = runBlocking {
        grantMicrophone()
        val saved = app.settingsRepository.audioInputPreferences.first()
        app.audioInputs.select(AudioInput.PHONE_KEY)
        val recorder = AudioRecorder()
        val actual = AtomicReference<CaptureInputState>()
        val error = AtomicReference<Throwable>()
        val samples = CountDownLatch(1)
        recorder.setObserver(object : CaptureObserver {
            override fun onInputChanged(state: CaptureInputState) { actual.set(state) }
        })
        val worker = Thread {
            try { recorder.startRecording({ if (actual.get()?.actual?.isPhone == true) samples.countDown() }) }
            catch (e: Throwable) { error.set(e); samples.countDown() }
        }
        try {
            worker.start()
            assertTrue("Phone PCM and actual-route confirmation", samples.await(8, TimeUnit.SECONDS))
            assertNull(error.get())
            assertTrue(actual.get().actual!!.isPhone)
        } finally {
            recorder.stop(); worker.join(3000)
            app.settingsRepository.updateAudioInput { saved }
        }
        assertFalse("Stop must join without a blocked native read", worker.isAlive)
    }

    @Test fun unavailableHeadsetFallsBackToRealPhoneFramesWithoutEndingRecording() = runBlocking {
        grantMicrophone()
        val initial = app.audioInputs.snapshotForRecording()
        val missing = AudioInput("missing-test-headset", "Unavailable headset", true, -10, -11)
        var state = CaptureInputState()
        var running = true
        val route = AndroidCaptureRoute(app, app.audioInputs,
            AudioInputState(initial.inputs + missing, InputPreferences(missing.key)), { running }, { state = it })
        val manager = app.getSystemService(AudioManager::class.java)
        val beforeMode = manager.mode
        val bytes = maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
        try {
            route.attach(record)
            record.startRecording(); route.started()
            val buffer = ShortArray(800)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var accepted = 0
            while (!state.receivingFallback && android.os.SystemClock.uptimeMillis() < deadline) {
                route.beforeRead(record, false)
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                route.afterRead(record, count, false)
                if (count > 0) accepted += count else Thread.sleep(10)
            }
            assertTrue("Fallback must deliver real PCM", accepted > 0)
            assertTrue(state.receivingFallback)
            assertTrue(state.actual!!.isPhone)
            assertEquals(missing, state.fallbackFrom)
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)
        } finally {
            running = false
            route.detach(record)
            record.stop(); record.release(); route.close()
        }
        assertEquals("Cleanup retains the prior audio mode", beforeMode, manager.mode)
    }

    @Test fun stopBeforeStartNeverAcquiresMicrophone() {
        val recorder = AudioRecorder()
        recorder.stop()
        recorder.startRecording({ fail("Stopped source produced PCM") })
        assertFalse(recorder.isRecording())
    }
}
