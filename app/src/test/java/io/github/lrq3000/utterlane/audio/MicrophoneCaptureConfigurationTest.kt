package io.github.lrq3000.utterlane.audio

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 36])
class MicrophoneCaptureConfigurationTest {
    private val record = mockk<AudioRecord> {
        every { sampleRate } returns 16000
        every { channelCount } returns 1
        every { audioFormat } returns AudioFormat.ENCODING_PCM_16BIT
        every { audioSource } returns MicrophoneSource.VOICE_RECOGNITION.androidSource
        every { bufferSizeInFrames } returns 3200
    }
    private val manager = mockk<AudioManager>(relaxed = true)
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.AUDIO_SERVICE) manager else super.getSystemService(name)
    }

    @Test fun matchingDeclaredPcmAndSourceAreAccepted() {
        assertNull(MicrophoneCaptureConfiguration.problem(context, record, MicrophoneOptions.HFP))
    }

    @Test fun defaultSourceDelegatesToAndroidInsteadOfDemandingTheDefaultSentinel() {
        every { record.audioSource } returns MicrophoneSource.MIC.androidSource
        assertNull(MicrophoneCaptureConfiguration.problem(context, record, MicrophoneOptions.STANDARD))
    }

    @Test fun anExplicitSourceCannotBeSilentlyReplaced() {
        every { record.audioSource } returns MicrophoneSource.MIC.androidSource
        val problem = MicrophoneCaptureConfiguration.problem(context, record, MicrophoneOptions.HFP)
        assertNotNull(problem)
        assertTrue(problem!!.contains("VOICE_RECOGNITION"))
    }

    @Test fun unprocessedNeedsPositiveAdvertisedPlatformSupport() {
        every { record.audioSource } returns MicrophoneSource.UNPROCESSED.androidSource
        for (advertised in listOf(null, "false")) {
            every { manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) } returns advertised
            val problem = MicrophoneCaptureConfiguration.problem(context, record,
                MicrophoneOptions.HFP.copy(source = MicrophoneSource.UNPROCESSED))
            assertNotNull(problem)
            assertTrue(problem!!.contains("UNPROCESSED"))
        }
    }

    @Test fun supportedUnprocessedRetainsTheExplicitChoice() {
        every { record.audioSource } returns MicrophoneSource.UNPROCESSED.androidSource
        every { manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) } returns "true"
        assertNull(MicrophoneCaptureConfiguration.problem(context, record,
            MicrophoneOptions.HFP.copy(source = MicrophoneSource.UNPROCESSED)))
    }

    @Test fun incompatibleNativePcmCannotBeWrittenWithTheFixedMono16kHeader() {
        every { record.sampleRate } returns 48000
        assertNotNull(MicrophoneCaptureConfiguration.problem(context, record, MicrophoneOptions.HFP))
        every { record.sampleRate } returns 16000
        every { record.channelCount } returns 2
        assertNotNull(MicrophoneCaptureConfiguration.problem(context, record, MicrophoneOptions.HFP))
        every { record.channelCount } returns 1
        every { record.audioFormat } returns AudioFormat.ENCODING_PCM_FLOAT
        assertNotNull(MicrophoneCaptureConfiguration.problem(context, record, MicrophoneOptions.HFP))
    }

    @Test fun unknownClientCapacityCannotClaimBoundedRouteVerification() {
        every { record.bufferSizeInFrames } returns 0
        assertNotNull(MicrophoneCaptureConfiguration.problem(context, record, MicrophoneOptions.HFP))
    }
}
