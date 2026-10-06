package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioSegmenterOptionsTest {
    private suspend fun windows(audio: ShortArray, options: RuntimeOptions?, block: Int = audio.size,
                                diarized: Boolean = false): List<AudioWindow> {
        val out = mutableListOf<AudioWindow>()
        val segmenter = AudioSegmenter(sampleRate = 100, options = options, flushPendingOnFinish = diarized) { out += it }
        for (start in audio.indices step block) segmenter.accept(audio.copyOfRange(start, minOf(start + block, audio.size)))
        segmenter.finish()
        segmenter.finish()
        return out
    }

    private fun sameWindows(expected: List<AudioWindow>, actual: List<AudioWindow>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertEquals(a.startSample, b.startSample)
            assertEquals(a.ownedStart, b.ownedStart)
            assertEquals(a.ownedEnd, b.ownedEnd)
            assertEquals(a.isFinal, b.isFinal)
            assertArrayEquals(a.samples, b.samples)
        }
    }

    @Test fun defaultOptionsKeepOffBaselinePcmCutsAndArbitraryPartitions(): Unit = runBlocking {
        val audio = ShortArray(3545) { if (it in 1200..1500) 0 else (1000 + it % 500).toShort() }
        val baseline = windows(audio, null)
        assertEquals(1000L, baseline.first().ownedEnd)
        assertEquals(1300L, baseline[1].ownedEnd)
        for (block in listOf(1, 137, audio.size)) sameWindows(baseline, windows(audio, RuntimeOptions(), block))
    }

    @Test fun leftAndRightContextAreIndependentAndMinimumAndSilenceAreConfigurable(): Unit = runBlocking {
        val options = RuntimeOptions(asrWindowSeconds = 2.0, asrMinSeconds = 1.0,
            asrLeftContextSeconds = .2, asrRightContextSeconds = .5,
            silenceDurationMs = 200, silenceAmplitude = 500)
        val audio = ShortArray(460) { if (it in 120..145) 400 else 1000 }
        val out = windows(audio, options, 17)
        assertEquals(140L, out[0].ownedEnd)
        assertEquals(190, out[0].samples.size)
        assertEquals(120L, out[1].startSample)
        assertEquals(340L, out[1].ownedEnd)
        assertEquals(270, out[1].samples.size)
        assertArrayEquals(audio.copyOfRange(120, 390), out[1].samples)
    }

    @Test fun zeroRightContextUsesOrdinaryDeliveryTimingEvenAtExactFinalBoundary(): Unit = runBlocking {
        val options = RuntimeOptions(asrWindowSeconds = 1.0, asrMinSeconds = 1.0,
            asrLeftContextSeconds = 0.0, asrRightContextSeconds = 0.0)
        val out = mutableListOf<AudioWindow>()
        val segmenter = AudioSegmenter(sampleRate = 100, options = options, flushPendingOnFinish = true) { out += it }
        val audio = ShortArray(200) { 1000 }
        segmenter.accept(audio)
        assertEquals(2, out.size)
        assertFalse(out.last().isFinal)
        segmenter.finish()
        sameWindows(windows(audio, options, 1), out)
        assertEquals(200L, out.last().ownedEnd)
    }

    @Test fun legacyPositionalAndTrailingLambdaConstructorsRemainValidAndCeilingIsFixed() {
        val consume: suspend (AudioWindow) -> Unit = {}
        AudioSegmenter(100, 1, .1, false, consume)
        AudioSegmenter(100, 1, .1, false) { }
        assertThrows(IllegalArgumentException::class.java) { AudioSegmenter(maxSeconds = 13) { } }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSegmenter(options = RuntimeOptions(asrWindowSeconds = 11.0)) { }
        }
    }

    @Test fun diarizedFinalCutsNeverExceedTwelveSecondPcmCeiling(): Unit = runBlocking {
        val out = mutableListOf<AudioWindow>()
        val segmenter = AudioSegmenter(flushPendingOnFinish = true, options = RuntimeOptions()) { out += it }
        segmenter.accept(ShortArray(335999) { 1000 })
        segmenter.finish()
        assertTrue(out.last().isFinal)
        assertEquals(335999L, out.last().ownedEnd)
        assertTrue(out.all { it.samples.size <= 192000 })
    }

    @Test fun rightContextLongerThanOwnedWindowCannotOverflowOrSkipCuts(): Unit = runBlocking {
        val options = RuntimeOptions(asrWindowSeconds = 1.0, asrMinSeconds = 1.0,
            asrLeftContextSeconds = 0.0, asrRightContextSeconds = 3.0)
        val audio = ShortArray(1045) { 1000 }
        val out = windows(audio, options, 13)
        assertTrue(out.all { it.ownedEnd - it.ownedStart <= 100 })
        assertEquals(1045L, out.last().ownedEnd)
        assertTrue(out.all { it.samples.size <= 400 })
        sameWindows(out, windows(audio, options, audio.size))
    }
}
