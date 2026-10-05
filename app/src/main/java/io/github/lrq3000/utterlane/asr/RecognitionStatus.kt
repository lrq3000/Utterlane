package io.github.lrq3000.utterlane.asr

enum class RecognitionStage {
    IDLE, QUEUED, CONNECTING, LOADING, WARMUP, ASR, SPEAKER_LOAD, SPEAKERS,
    SPEAKER_FEATURES, SPEAKER_TRANSFORMER, SPEAKER_CACHE, FINISHED, ERROR, WORKING
}

/** UI/log boundary: no native messages, model paths, transcript or arbitrary stage names. */
data class RecognitionStatus private constructor(
    val requestId: Int,
    val stage: RecognitionStage,
    val scope: String,
    val elapsedMillis: Long,
    val sinceProgressMillis: Long,
    val completedUnits: Long,
    val active: Boolean,
    val opaque: Boolean
) {
    companion object {
        // Exact matches, not prefixes: a future native scope must be deliberately reviewed
        // before it can enter production diagnostics. Lookups and retained state are O(1).
        private val stages = mapOf(
            "idle" to RecognitionStage.IDLE, "queued" to RecognitionStage.QUEUED,
            "connecting" to RecognitionStage.CONNECTING, "executing" to RecognitionStage.WORKING,
            "model_load" to RecognitionStage.LOADING, "model_load_complete" to RecognitionStage.LOADING,
            "warmup" to RecognitionStage.WARMUP, "warmup_complete" to RecognitionStage.WARMUP,
            "asr" to RecognitionStage.ASR, "asr_complete" to RecognitionStage.ASR,
            "asr/encoded_audio" to RecognitionStage.ASR,
            "warmup/encoded_audio" to RecognitionStage.WARMUP,
            "speaker_load" to RecognitionStage.SPEAKER_LOAD, "speaker_load_complete" to RecognitionStage.SPEAKER_LOAD,
            "speaker_inference" to RecognitionStage.SPEAKERS, "speaker_push_complete" to RecognitionStage.SPEAKERS,
            "speaker/features" to RecognitionStage.SPEAKER_FEATURES,
            "speaker/transformer" to RecognitionStage.SPEAKER_TRANSFORMER,
            "speaker/cache" to RecognitionStage.SPEAKER_CACHE,
            "speaker_load/features" to RecognitionStage.SPEAKER_FEATURES,
            "speaker_load/transformer" to RecognitionStage.SPEAKER_TRANSFORMER,
            "speaker_load/cache" to RecognitionStage.SPEAKER_CACHE,
            "completed" to RecognitionStage.FINISHED, "error" to RecognitionStage.ERROR
        )

        fun from(activity: RecognitionActivity): RecognitionStatus {
            val known = if (activity.stage.length <= 64) stages[activity.stage] else null
            val stage = if (!activity.active && activity.message != null) RecognitionStage.ERROR
                else known ?: RecognitionStage.WORKING
            return RecognitionStatus(activity.requestId, stage, if (known == null) "unknown" else activity.stage,
                activity.elapsedMillis.coerceAtLeast(0), activity.sinceProgressMillis.coerceAtLeast(0),
                activity.completedUnits.coerceAtLeast(0), activity.active,
                activity.active && (activity.message != null || known == null))
        }
    }
}
