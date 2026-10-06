package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import org.junit.Assert.*
import org.junit.Test

class WorkerSpeakerFinalizationTest {
    private val options = RuntimeOptions(asrRightContextSeconds = 0.0)

    private class Stream(private val failFinal: Boolean = false) : SpeakerProbabilityStream {
        var pushes = 0
        var closes = 0
        override fun push(samples: ShortArray, final: Boolean): FloatArray {
            pushes++
            if (!final) return floatArrayOf()
            check(!failFinal) { "Injected native drain failure" }
            assertTrue(samples.isEmpty())
            return FloatArray(100 * 8) { if (it % 8 == 0) .95f else .01f }
        }
        override fun close() { closes++ }
    }

    private class Backend : RecognitionBackend {
        var calls = 0
        override fun transcribeWindow(samples: ShortArray): WindowResult {
            calls++
            return WindowResult(arrayOf(" tail"), floatArrayOf(.9f))
        }
        override fun close() {}
    }

    private fun pending(backend: Backend, stream: SpeakerProbabilityStream, count: Int = 0): RecognitionWorkerService.SpeakerSession {
        val processor = DiarizedWindowProcessor(backend, stream, count, options = options)
        processor.process(AudioWindow(ShortArray(16000), 0, 0, 16000))
        return RecognitionWorkerService.SpeakerSession(processor, count, options)
    }

    @Test fun finishRemovesClosesAndDrainsOnlyTheExistingSessionOnce() {
        val backend = Backend()
        val stream = Stream()
        val sessions = mutableMapOf(1L to pending(backend, stream))
        assertEquals(listOf(SpeechSpan("tail", 0)), RecognitionWorkerService.finishSpeakerSession(sessions, 1) { options })
        assertTrue(sessions.isEmpty())
        assertEquals(2, stream.pushes)
        assertEquals(1, stream.closes)
        assertEquals(1, backend.calls)
        assertTrue(RecognitionWorkerService.finishSpeakerSession(sessions, 1) {
            error("An already finalized session must not be recreated")
        }.isEmpty())
        assertEquals(2, stream.pushes)
        assertEquals(1, stream.closes)
    }

    @Test fun noAudioAndDefaultBackendFinishAreSafeNoOps() {
        val sessions = mutableMapOf<Long, RecognitionWorkerService.SpeakerSession>()
        assertTrue(RecognitionWorkerService.finishSpeakerSession(sessions, 1) { error("No session to configure or create") }.isEmpty())
        val backend = Backend()
        assertTrue(backend.finishSpeakers(1).isEmpty())
        assertEquals(0, backend.calls)
    }

    @Test fun fixedOneFinalizationNeedsNeitherNativeModelNorAnotherAsrCall() {
        val backend = Backend()
        val stream = RecognitionWorkerService.speakerStream(1) { error("Fixed one cannot load native weights") }
        val sessions = mutableMapOf(1L to pending(backend, stream, count = 1))
        assertTrue(RecognitionWorkerService.finishSpeakerSession(sessions, 1) { options }.isEmpty())
        assertTrue(sessions.isEmpty())
        assertEquals(1, backend.calls)
    }

    @Test fun mismatchingOrMalformedOptionsCloseAndRemoveWithoutPushingNative() {
        for (malformed in listOf(false, true)) {
            val stream = Stream()
            val sessions = mutableMapOf(1L to pending(Backend(), stream))
            assertThrows(IllegalArgumentException::class.java) {
                RecognitionWorkerService.finishSpeakerSession(sessions, 1) {
                    require(!malformed) { "Malformed wire options" }
                    options.copy(labelLookaheadMs = options.labelLookaheadMs + 1)
                }
            }
            assertTrue(sessions.isEmpty())
            assertEquals(1, stream.pushes)
            assertEquals(1, stream.closes)
        }
    }

    @Test fun failedNativeDrainClosesAndRemovesTheSessionButPreservesOtherRecordings() {
        val stream = Stream(failFinal = true)
        val other = pending(Backend(), Stream())
        val sessions = mutableMapOf(1L to pending(Backend(), stream), 2L to other)
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                RecognitionWorkerService.finishSpeakerSession(sessions, 1) { options }
            }
            assertEquals("Injected native drain failure", error.message)
            assertEquals(setOf(2L), sessions.keys)
            assertSame(other, sessions[2])
            assertEquals(2, stream.pushes)
            assertEquals(1, stream.closes)
        } finally { other.processor.close() }
    }
}
