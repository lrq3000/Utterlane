package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class RecognitionProgressReporterTest {
    @Test fun cumulativeCountersOnlyAdvanceOnActualIncreaseWithinEachStage() {
        val packets = mutableListOf<RecognitionProgress>()
        val reporter = RecognitionProgressReporter(1, packets::add)
        reporter.stage("queued")
        reporter.stage("executing")
        reporter.nativeProgress(0, "encoder")
        reporter.nativeProgress(4, "encoder")
        reporter.nativeProgress(4, "encoder")
        reporter.nativeProgress(2, "encoder")
        reporter.nativeProgress(1, "decoder")
        reporter.nativeProgress(5, "encoder")
        assertEquals(listOf(4L, 5L, 6L), packets.filter { it.completed }.map { it.completedUnits })
        assertTrue(packets.zipWithNext().all { (a, b) -> b.sequence > a.sequence })
        assertEquals(1, packets.map { it.requestId }.distinct().single())
    }

    @Test fun completedStagesAreCountedOnceAndClosedCallbacksAreIgnored() {
        val packets = mutableListOf<RecognitionProgress>()
        val reporter = RecognitionProgressReporter(1, packets::add)
        reporter.completeStage("model_load_complete")
        reporter.completeStage("model_load_complete")
        reporter.stage("warmup")
        reporter.completeStage("warmup_complete")
        reporter.close()
        reporter.nativeProgress(99, "encoder")
        reporter.stage("late")
        assertEquals(listOf(1L, 2L), packets.filter { it.completed }.map { it.completedUnits })
        assertTrue(packets.all { it.opaque })
    }

    @Test fun countersRestartForEachRequestAndRepeatedCallScope() {
        val packets = mutableListOf<RecognitionProgress>()
        val reporter = RecognitionProgressReporter(2, packets::add)
        val first = reporter.callback("asr")
        first(10, "encoder")
        val second = reporter.callback("asr")
        first(99, "encoder") // A previous native invocation no longer owns the callback.
        second(1, "encoder")
        assertEquals(listOf(10L, 11L), packets.filter { it.completed }.map { it.completedUnits })
    }

    @Test fun aReturnedInvocationCannotRenewTheNextStage() {
        val packets = mutableListOf<RecognitionProgress>()
        val reporter = RecognitionProgressReporter(2, packets::add)
        val callback = reporter.callback("asr")
        callback(1, "encoder")
        reporter.endCallback("asr")
        reporter.stage("speaker_inference")
        callback(2, "encoder")
        assertEquals("speaker_inference", packets.last().stage)
        assertEquals(1, packets.last().completedUnits)
    }
}
