package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class WorkerSpeakerStreamTest {
    @Test fun fixedOneDoesNotLoadAnyAuxiliaryWeightsOrConstructNativeStream() {
        val stream = RecognitionWorkerService.speakerStream(1) { error("Fixed-one must never construct native diarization") }
        assertEquals(0, stream.push(shortArrayOf(1), true).size)
        stream.close()
    }

    @Test fun autoAndMultipleSpeakersUseTheFactoryExactlyOnce() {
        for (count in listOf(0, 2, 8)) {
            var calls = 0
            val expected = object : SpeakerProbabilityStream {
                override fun push(samples: ShortArray, final: Boolean) = floatArrayOf()
                override fun close() {}
            }
            assertSame(expected, RecognitionWorkerService.speakerStream(count) { calls++; expected })
            assertEquals(1, calls)
        }
    }
}
