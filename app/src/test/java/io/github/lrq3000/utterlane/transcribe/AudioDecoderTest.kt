package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fake only the native extractor/codec boundary; exercise the real decode loop and resampler. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AudioDecoderTest {
    private val codec = mockk<MediaCodec>(relaxed = true)
    private lateinit var input: MediaFormat
    private lateinit var output: MediaFormat
    private var announceFormat = true
    private var buffers = listOf(pcm(100, 300))
    private var nextBuffer = 0
    private var released = 0
    private var outputCalls = 0

    @Before fun setUp() {
        input = format()
        output = format()
        mockkConstructor(MediaExtractor::class)
        every { anyConstructed<MediaExtractor>().setDataSource(any<String>()) } just Runs
        every { anyConstructed<MediaExtractor>().trackCount } returns 1
        every { anyConstructed<MediaExtractor>().getTrackFormat(0) } answers { input }
        every { anyConstructed<MediaExtractor>().selectTrack(any()) } just Runs
        every { anyConstructed<MediaExtractor>().release() } just Runs
        // Input is already drained by the fake codec. Avoid dependence on a host codec implementation.
        every { codec.dequeueInputBuffer(any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
        every { codec.outputFormat } answers { output }
        every { codec.dequeueOutputBuffer(any(), any()) } answers {
            check(outputCalls++ < 100) { "Decoder did not terminate" }
            if (announceFormat) {
                announceFormat = false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            } else {
                val index = nextBuffer++
                val info = firstArg<MediaCodec.BufferInfo>()
                info.set(0, buffers[index].remaining(), index * 100_000L,
                    if (index == buffers.lastIndex) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                index
            }
        }
        every { codec.getOutputBuffer(any()) } answers { buffers[firstArg()] }
        every { codec.releaseOutputBuffer(any(), false) } answers { released++; Unit }
        mockkStatic(MediaCodec::class)
        every { MediaCodec.createDecoderByType(any()) } returns codec
    }

    @After fun tearDown() = unmockkAll()

    @Test fun malformedOptionalDurationDoesNotBlockUsableAudio() = runBlocking {
        input.setString(MediaFormat.KEY_DURATION, "unknown")
        val progress = mutableListOf<Int?>()
        val samples = mutableListOf<Short>()
        decoder().decode("source", { samples.addAll(it.toList()) }, progress::add)
        assertEquals(listOf<Short>(100, 300), samples)
        assertEquals(listOf(null, 100), progress)
    }

    @Test fun nonpositiveDurationRemainsUnknown() = runBlocking {
        input.setLong(MediaFormat.KEY_DURATION, -1)
        val progress = mutableListOf<Int?>()
        decoder().decode("source", {}, progress::add)
        assertEquals(listOf(null, 100), progress)
    }

    @Test fun scansPastUnreadableMimeAndInvalidAudioDescriptors() = runBlocking {
        every { anyConstructed<MediaExtractor>().trackCount } returns 5
        every { anyConstructed<MediaExtractor>().getTrackFormat(0) } throws IllegalArgumentException("broken descriptor")
        every { anyConstructed<MediaExtractor>().getTrackFormat(1) } returns MediaFormat().apply { setInteger(MediaFormat.KEY_MIME, 4) }
        every { anyConstructed<MediaExtractor>().getTrackFormat(2) } returns format(rate = 0)
        every { anyConstructed<MediaExtractor>().getTrackFormat(3) } returns format(channels = -1)
        every { anyConstructed<MediaExtractor>().getTrackFormat(4) } returns input
        decoder().decode("source", {})
        verify(exactly = 1) { anyConstructed<MediaExtractor>().selectTrack(4) }
        verify(exactly = 0) { anyConstructed<MediaExtractor>().selectTrack(2) }
    }

    @Test fun invalidMandatoryInputReportsTrackAndKeyInsteadOfGuessing() {
        input.setString(MediaFormat.KEY_SAMPLE_RATE, "unknown")
        val error = assertThrows(Exception::class.java) { runBlocking { decoder().decode("source", {}) } }
        assertTrue(error.toString(), error.message.orEmpty().contains("track 0"))
        assertTrue(error.toString(), error.message.orEmpty().contains(MediaFormat.KEY_SAMPLE_RATE))
        assertNotNull("Retain the metadata getter failure", error.cause)
        verify(exactly = 0) { MediaCodec.createDecoderByType(any()) }
    }

    @Test fun missingOutputRateHasContextAndIsNotReplacedWithInputMetadata() {
        output = MediaFormat().apply { setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1) }
        assertOutputError(MediaFormat.KEY_SAMPLE_RATE)
    }

    @Test fun unannouncedInvalidOutputStillReleasesItsBuffer() {
        announceFormat = false
        output.setInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
        assertOutputError(MediaFormat.KEY_SAMPLE_RATE)
        assertEquals(1, released)
    }

    @Test fun zeroOutputChannelsHasContext() {
        output.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
        assertOutputError(MediaFormat.KEY_CHANNEL_COUNT)
    }

    @Test fun malformedOutputEncodingHasContextAndRetainsCause() {
        output.setString(MediaFormat.KEY_PCM_ENCODING, "float")
        assertNotNull(assertOutputError(MediaFormat.KEY_PCM_ENCODING).cause)
    }

    @Test fun unsupportedOutputEncodingHasContext() {
        output.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_8BIT)
        assertOutputError(MediaFormat.KEY_PCM_ENCODING)
    }

    @Test fun unannouncedFloatOutputUsesTheActualDecoderFormat() = runBlocking {
        announceFormat = false
        output.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        buffers = listOf(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putFloat(-0.25f); putFloat(0.25f); flip() })
        val samples = mutableListOf<Short>()
        decoder().decode("source", { samples.addAll(it.toList()) })
        assertEquals(listOf<Short>(-8192, 8192), samples)
        assertEquals(1, released)
    }

    @Test fun tailSamplesSurviveResamplingAndBuffersAreReleasedBeforeSuspending() = runBlocking {
        output = format(rate = 8000)
        val samples = mutableListOf<Short>()
        decoder().decode("source", {
            assertEquals(1, released)
            delay(1) // Recognition can suspend without keeping a codec output slot leased.
            samples.addAll(it.toList())
        })
        assertEquals(listOf<Short>(100, 200, 300, 300), samples)
        verify(exactly = 1) { codec.stop() }
        verify(exactly = 1) { codec.release() }
        verify(exactly = 1) { anyConstructed<MediaExtractor>().release() }
    }

    @Test fun longInputIsDeliveredIncrementallyInCodecSizedChunks() = runBlocking {
        buffers = List(64) { pcm(*IntArray(2048) { 100 }) }
        var chunks = 0
        var count = 0
        decoder().decode("source", {
            chunks++
            assertEquals(chunks, released)
            assertTrue(it.size <= 2048)
            count += it.size
        })
        assertEquals(64, chunks)
        assertEquals(64 * 2048, count)
    }

    @Test fun partialChannelFramesSurviveCodecBufferBoundaries() = runBlocking {
        output = format(channels = 2)
        buffers = listOf(pcm(100, 300, 500), pcm(700))
        val samples = mutableListOf<Short>()
        decoder().decode("source", { samples.addAll(it.toList()) })
        assertEquals(listOf<Short>(200, 600), samples)
    }

    @Test fun cleanupFailureCannotReplaceCancellationAndEveryResourceIsReleased() {
        val cancellation = CancellationException("Stopped by user")
        val cleanup = IllegalStateException("Codec is already stopped")
        every { codec.stop() } throws cleanup
        val error = assertThrows(Exception::class.java) {
            runBlocking { decoder().decode("source", { throw cancellation }) }
        }
        // Coroutine stack-trace recovery may copy the exception at the dispatcher
        // boundary, retaining the original as its cause.
        assertTrue(error is CancellationException)
        assertTrue(error === cancellation || error.cause === cancellation)
        assertTrue(cancellation.suppressed.contains(cleanup))
        assertEquals(1, released)
        verify(exactly = 1) { codec.release() }
        verify(exactly = 1) { anyConstructed<MediaExtractor>().release() }
    }

    @Test fun configurationFailureIsPreservedAndDoesNotStopAnUnstartedCodec() {
        val failure = IllegalArgumentException("Codec rejected source")
        every { codec.configure(any<MediaFormat>(), null, null, 0) } throws failure
        every { codec.release() } throws IllegalStateException("release failed")
        val error = assertThrows(Exception::class.java) { runBlocking { decoder().decode("source", {}) } }
        assertTrue(error === failure || error.cause === failure)
        assertEquals("release failed", failure.suppressed.single().message)
        verify(exactly = 0) { codec.stop() }
        verify(exactly = 1) { anyConstructed<MediaExtractor>().release() }
    }

    @Test fun trackScanningDoesNotSwallowCancellation() {
        every { anyConstructed<MediaExtractor>().trackCount } returns 2
        every { anyConstructed<MediaExtractor>().getTrackFormat(0) } throws CancellationException("Stop")
        assertThrows(CancellationException::class.java) { runBlocking { decoder().decode("source", {}) } }
        verify(exactly = 0) { anyConstructed<MediaExtractor>().getTrackFormat(1) }
        verify(exactly = 1) { anyConstructed<MediaExtractor>().release() }
    }

    private fun assertOutputError(key: String): Exception {
        val error = assertThrows(Exception::class.java) { runBlocking { decoder().decode("source", {}) } }
        assertTrue(error.toString(), error.message.orEmpty().contains("track 0"))
        assertTrue(error.toString(), error.message.orEmpty().contains("output"))
        assertTrue(error.toString(), error.message.orEmpty().contains(key))
        return error
    }

    private fun decoder() = AudioDecoder(RuntimeEnvironment.getApplication())
    private fun format(rate: Int = 16000, channels: Int = 1) = MediaFormat.createAudioFormat("audio/raw", rate, channels)
    private fun pcm(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder()).apply {
        values.forEach { putShort(it.toShort()) }; flip()
    }
}
