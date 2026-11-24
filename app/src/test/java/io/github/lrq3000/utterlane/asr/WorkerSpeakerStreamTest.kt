package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class WorkerSpeakerStreamTest {
    @Test fun fixedOneDoesNotLoadAnyAuxiliaryWeightsOrConstructNativeStream() {
        val stream = RecognitionWorkerService.speakerStream(1) { error("Fixed-one must never construct native diarization") }
        assertEquals(0, stream.push(shortArrayOf(1), true).size)
        stream.close()
    }

    @Test fun autoAndMultipleSpeakersOpenOnceWhenPushingAudioAndCloseOnce() {
        for (count in listOf(0, 2, 8)) {
            var opens = 0
            var closes = 0
            val stream = RecognitionWorkerService.speakerStream(count) {
                opens++
                object : SpeakerProbabilityStream {
                    override fun push(samples: ShortArray, final: Boolean) = floatArrayOf(samples.single().toFloat())
                    override fun close() { closes++ }
                }
            }
            assertEquals(0, opens)
            assertArrayEquals(floatArrayOf(12f), stream.push(shortArrayOf(12), false), 0f)
            assertArrayEquals(floatArrayOf(34f), stream.push(shortArrayOf(34), true), 0f)
            stream.close()
            assertEquals(1, opens)
            assertEquals(1, closes)
        }
    }
}
