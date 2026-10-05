package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import org.junit.Assert.*
import org.junit.Test

class RecognitionRuntimeConfigurationTest {
    @Test fun savedThreadAndBudgetChangesWaitForAllSessionsToDrain() {
        val configuration = RecognitionRuntimeConfiguration()
        val original = RuntimeOptions()
        configuration.applied(original)
        val saved = original.copy(asrThreads = 2, inferenceStallSeconds = 10)
        repeat(3) {
            assertEquals(RecognitionRuntimeConfiguration.Change.KEEP, configuration.change(saved, hasSessions = true))
        }
        assertEquals(RecognitionRuntimeConfiguration.Change.RELOAD, configuration.change(saved, hasSessions = false))
        configuration.applied(saved)
        assertEquals(RecognitionRuntimeConfiguration.Change.KEEP, configuration.change(saved, hasSessions = false))
    }

    @Test fun idleBudgetChangeReconfiguresWithoutReloadingWeights() {
        val configuration = RecognitionRuntimeConfiguration()
        val original = RuntimeOptions()
        configuration.applied(original)
        val disabled = original.copy(workerConnectSeconds = 0, prepareStallSeconds = 0, inferenceStallSeconds = 0)
        assertEquals(RecognitionRuntimeConfiguration.Change.KEEP, configuration.change(disabled, hasSessions = true))
        assertEquals(RecognitionRuntimeConfiguration.Change.CONFIGURE, configuration.change(disabled, hasSessions = false))
        configuration.applied(disabled)
        assertEquals(RecognitionRuntimeConfiguration.Change.KEEP, configuration.change(disabled, hasSessions = false))
    }

    @Test fun nativeThreadAutoAndExplicitChangesRequireFreshPreparation() {
        val configuration = RecognitionRuntimeConfiguration()
        val defaults = RuntimeOptions()
        assertEquals(RecognitionRuntimeConfiguration.Change.RELOAD, configuration.change(defaults, hasSessions = false))
        configuration.applied(defaults)
        assertEquals(RecognitionRuntimeConfiguration.Change.RELOAD, configuration.change(defaults.copy(asrThreads = 0), hasSessions = false))
        // The diarizer is per-session: changing its threads cannot reload shared ASR.
        assertEquals(RecognitionRuntimeConfiguration.Change.CONFIGURE, configuration.change(defaults.copy(diarizationThreads = 2), hasSessions = false))
    }
}
