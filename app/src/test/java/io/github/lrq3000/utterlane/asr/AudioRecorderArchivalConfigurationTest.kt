package io.github.lrq3000.utterlane.asr

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import io.github.lrq3000.utterlane.archive.PcmFormat
import io.github.lrq3000.utterlane.audio.MicrophoneOptions
import io.github.lrq3000.utterlane.audio.MicrophoneSource
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the production format probe; only Android's native recorder is fake. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AudioRecorderArchivalConfigurationTest {
    @After fun tearDown() = unmockkAll()

    @Test fun nonNativeProbeHonorsEveryExplicitSourceAndRuntimeCapacity() {
        mockkConstructor(AudioRecord::class)
        mockkStatic(AudioRecord::class)
        every { AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) } returns 1920
        for (source in MicrophoneSource.entries) {
            val arguments = arrayOf<Matcher<*>>(EqMatcher(source.androidSource), EqMatcher(48000),
                EqMatcher(AudioFormat.CHANNEL_IN_STEREO), EqMatcher(AudioFormat.ENCODING_PCM_16BIT), EqMatcher(48000))
            every { constructedWith<AudioRecord>(*arguments).state } returns AudioRecord.STATE_INITIALIZED
            every { constructedWith<AudioRecord>(*arguments).sampleRate } returns 48000
            every { constructedWith<AudioRecord>(*arguments).channelCount } returns 2
            every { constructedWith<AudioRecord>(*arguments).recordingState } returns AudioRecord.RECORDSTATE_STOPPED
            every { constructedWith<AudioRecord>(*arguments).release() } just Runs
            val recorder = AudioRecorder()
            recorder.configureMicrophone(MicrophoneOptions.HFP.copy(source = source))
            assertEquals(PcmFormat(48000, 2), recorder.configureFormat(PcmFormat(48000, 2), RuntimeOptions(captureBufferSeconds = .25)))
            recorder.releaseAfterCapture()
            verify(exactly = 1) { constructedWith<AudioRecord>(*arguments).release() }
        }
    }
}
