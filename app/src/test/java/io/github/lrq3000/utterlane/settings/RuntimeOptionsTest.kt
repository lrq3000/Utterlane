package io.github.lrq3000.utterlane.settings

import org.junit.Assert.*
import org.junit.Test

class RuntimeOptionsTest {
    @Test fun defaultsPreserveRuntimeContract() {
        val options = RuntimeOptions().requireValid()
        assertEquals(30L, options.workerConnectSeconds)
        assertEquals(300L, options.prepareStallSeconds)
        assertEquals(300L, options.inferenceStallSeconds)
        assertEquals(0L, options.absoluteOperationSeconds)
        assertEquals(4, options.asrThreads)
        assertEquals(4, options.diarizationThreads)
        assertEquals("low_latency", options.diarizationMode)
        assertEquals(16, options.diarizationBatch)
        assertEquals(1000, options.unknownBridgeMs)
        assertEquals(10.0, options.asrWindowSeconds, 0.0)
        assertEquals(20, options.queueSeconds)
        assertEquals(50, options.captureBlockMs)
        assertEquals(1500, options.wakeRecoveryMs)
        assertEquals(5000, options.wakeReopenMs)
        assertEquals(264, options.nativeCacheFrames)
        assertEquals(264, options.nativeFifoFrames)
        assertEquals(222, options.nativeUpdateFrames)
        assertEquals(options, RuntimeOptions.fromMap(emptyMap()))
        assertEquals(options, options.copy(diarizationMode = "very_low_latency").resetGroup(RuntimeOptionGroup.DIARIZATION))
    }

    @Test fun everyFieldRoundTripsWithStableKeys() {
        val options = RuntimeOptions(12, 13, 14, 15, 0, 8, "very_low_latency", 2,
            0.7f, 0.1f, 250, 400, 350, 150, 8.0, 2.0, 0.5, 1.5, 700, 250,
            30, 2.0, 100, 2000, 6000, 45, 90, 128, 128, 100, true)
        assertEquals(35, options.toMap().size)
        assertEquals("12", options.toMap()["worker_connect_seconds"])
        assertEquals("true", options.toMap()["diagnostics"])
        assertEquals(options, RuntimeOptions.fromMap(options.toMap()))
    }

    @Test fun attributionPolicyKeysHaveDefaultsAndRoundTripInTheDiarizationGroup() {
        val defaults = mapOf("strong_speaker_threshold" to "0.7", "strong_speaker_margin" to "0.2",
            "strong_confirmation_ms" to "30", "word_fallback_ms" to "400")
        val configured = mapOf("strong_speaker_threshold" to "0.85", "strong_speaker_margin" to "0.25",
            "strong_confirmation_ms" to "60", "word_fallback_ms" to "900")
        assertEquals(defaults, RuntimeOptions().toMap().filterKeys { it in defaults })
        val snapshot = RuntimeOptions.fromMap(configured)
        assertEquals(configured, snapshot.toMap().filterKeys { it in configured })
        assertEquals(snapshot, RuntimeOptions.fromMap(snapshot.toMap()))
        assertEquals(defaults.keys, RuntimeOptions.fields.filter { it.key in defaults && it.group == RuntimeOptionGroup.DIARIZATION }
            .map { it.key }.toSet())
        assertEquals(RuntimeOptions(), snapshot.resetGroup(RuntimeOptionGroup.DIARIZATION))
        assertEquals(RuntimeOptions().toMap().keys, RuntimeOptions.fields.map { it.key }.toSet())
    }

    @Test fun attributionPolicyBoundsAreValidatedWithoutRejectingEffectiveFloorsAndDurationClamps() {
        for ((key, invalid) in listOf("strong_speaker_threshold" to "NaN", "strong_speaker_threshold" to "1.1",
            "strong_speaker_margin" to "-0.1", "strong_confirmation_ms" to "-1", "strong_confirmation_ms" to "10001",
            "word_fallback_ms" to "9", "word_fallback_ms" to "2001")) {
            assertNull("$key=$invalid", RuntimeOptions.parseDraft(mapOf(key to invalid)).options)
            assertEquals(RuntimeOptions(), RuntimeOptions.fromMap(mapOf(key to invalid)))
        }
        val values = mapOf("speaker_threshold" to "0.9", "strong_speaker_threshold" to "0.6",
            "speaker_margin" to "0.3", "strong_speaker_margin" to "0.1",
            "speaker_confirmation_ms" to "0", "strong_confirmation_ms" to "10000", "word_fallback_ms" to "2000")
        assertNotNull(RuntimeOptions.parseDraft(values).options)
        assertEquals(values, RuntimeOptions.fromMap(values).toMap().filterKeys { it in values })
        assertNotNull(RuntimeOptions.parseDraft(mapOf("strong_confirmation_ms" to "0", "word_fallback_ms" to "10")).options)
    }

    @Test fun strictWritesRejectNonFiniteAndUnsafeValues() {
        val defaults = RuntimeOptions()
        listOf(
            defaults.copy(asrWindowSeconds = Double.NaN),
            defaults.copy(speakerThreshold = Float.POSITIVE_INFINITY),
            defaults.copy(workerConnectSeconds = -1),
            defaults.copy(downloadReadSeconds = 86401),
            defaults.copy(asrThreads = 33), defaults.copy(diarizationThreads = -1),
            defaults.copy(diarizationMode = "gpu"), defaults.copy(diarizationBatch = 17),
            defaults.copy(asrMinSeconds = 11.0), defaults.copy(asrWindowSeconds = 10.1),
            defaults.copy(silenceDurationMs = 0), defaults.copy(silenceAmplitude = 0),
            defaults.copy(captureBlockMs = 201), defaults.copy(queueSeconds = 0),
            defaults.copy(nativeCacheFrames = 17), defaults.copy(nativeCacheFrames = 1032),
            defaults.copy(nativeFifoFrames = 15), defaults.copy(nativeUpdateFrames = 265)
        ).forEach { invalid ->
            assertFalse(invalid.validationErrors().isEmpty())
            val error = assertThrows(IllegalArgumentException::class.java) { invalid.requireValid() }
            assertFalse(error.message.isNullOrBlank())
        }
        defaults.copy(workerConnectSeconds = 0, prepareStallSeconds = 0,
            inferenceStallSeconds = 0, downloadConnectSeconds = 0, downloadReadSeconds = 0,
            asrThreads = 0, diarizationThreads = 32, diarizationBatch = 16,
            nativeCacheFrames = 16, nativeFifoFrames = 16, nativeUpdateFrames = 16).requireValid()
    }

    @Test fun persistedCorruptionDefaultsInvalidFieldsAndDependentGroupsOnly() {
        val restored = RuntimeOptions.fromMap(mapOf(
            "asr_threads" to "8", "diarization_mode" to "invalid", "diarization_batch" to "oops", "diagnostics" to "yes",
            "speaker_threshold" to "NaN", "asr_window_seconds" to "12",
            "native_fifo_frames" to "16", "download_read_seconds" to "-1",
            "future_key" to "ignored"
        ))
        assertEquals(RuntimeOptions(asrThreads = 8), restored)
        restored.requireValid()
    }

    @Test fun draftsAreStrictAndAllowDependentFieldsToChangeTogether() {
        val initial = RuntimeOptions().toMap()
        val invalid = RuntimeOptions.parseDraft(initial + ("native_fifo_frames" to "32"))
        assertNull(invalid.options)
        assertTrue(invalid.errors.containsKey("native_update_frames"))
        val valid = RuntimeOptions.parseDraft(initial + mapOf("native_fifo_frames" to "32", "native_update_frames" to "32"))
        assertEquals(32, valid.options!!.nativeFifoFrames)
        assertNull(RuntimeOptions.parseDraft(initial + ("capture_block_ms" to "1.5")).options)
        assertNull(RuntimeOptions.parseDraft(initial + ("diagnostics" to "yes")).options)
    }

    @Test fun resettingOneGroupPreservesOtherGroupsAndAllDefaultsRemainValid() {
        val options = RuntimeOptions(asrThreads = 8, nativeFifoFrames = 32, nativeUpdateFrames = 32)
        assertEquals(RuntimeOptions(asrThreads = 8), options.resetGroup(RuntimeOptionGroup.EXPERIMENTAL))
        assertEquals(options.copy(asrThreads = 4), options.resetGroup(RuntimeOptionGroup.CPU))
        RuntimeOptionGroup.entries.forEach { options.resetGroup(it).requireValid() }
        assertEquals(1, RuntimeOptions.resolveThreads(0, 1))
        assertEquals(4, RuntimeOptions.resolveThreads(0, 64))
        assertEquals(8, RuntimeOptions.resolveThreads(8, 2))
    }
}
