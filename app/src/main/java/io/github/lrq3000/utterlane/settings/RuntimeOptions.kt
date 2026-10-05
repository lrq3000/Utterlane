package io.github.lrq3000.utterlane.settings

/** Immutable operation snapshot. Never collect preference changes inside an active operation. */
data class RuntimeOptions(
    val workerConnectSeconds: Long = 30,
    val prepareStallSeconds: Long = 300,
    val inferenceStallSeconds: Long = 300,
    val absoluteOperationSeconds: Long = 0,
    val asrThreads: Int = 4,
    val diarizationThreads: Int = 4,
    val diarizationMode: String = "very_low_latency",
    val diarizationBatch: Int = 1,
    val speakerThreshold: Float = 0.5f,
    val speakerMargin: Float = 0.08f,
    val speakerConfirmationMs: Int = 200,
    val unknownBridgeMs: Int = 350,
    val labelLookaheadMs: Int = 300,
    val alignmentToleranceMs: Int = 120,
    val asrWindowSeconds: Double = 10.0,
    val asrMinSeconds: Double = 3.0,
    val asrLeftContextSeconds: Double = 1.0,
    val asrRightContextSeconds: Double = 1.0,
    val silenceDurationMs: Int = 600,
    val silenceAmplitude: Int = 200,
    val queueSeconds: Int = 20,
    val captureBufferSeconds: Double = 1.0,
    val captureBlockMs: Int = 50,
    val wakeRecoveryMs: Int = 1500,
    val wakeReopenMs: Int = 5000,
    val downloadConnectSeconds: Long = 30,
    val downloadReadSeconds: Long = 60,
    val nativeCacheFrames: Int = 264,
    val nativeFifoFrames: Int = 264,
    val nativeUpdateFrames: Int = 222,
    val diagnostics: Boolean = false,
    val strongSpeakerThreshold: Float = 0.7f,
    val strongSpeakerMargin: Float = 0.2f,
    val strongConfirmationMs: Int = 30,
    val wordFallbackMs: Int = 400
) {
    // Explicit wire keys survive property renames and are shared by DataStore and worker IPC.
    fun toMap(): Map<String, String> = mapOf(
        "worker_connect_seconds" to workerConnectSeconds.toString(),
        "prepare_stall_seconds" to prepareStallSeconds.toString(),
        "inference_stall_seconds" to inferenceStallSeconds.toString(),
        "absolute_operation_seconds" to absoluteOperationSeconds.toString(),
        "asr_threads" to asrThreads.toString(), "diarization_threads" to diarizationThreads.toString(),
        "diarization_mode" to diarizationMode, "diarization_batch" to diarizationBatch.toString(),
        "speaker_threshold" to speakerThreshold.toString(), "speaker_margin" to speakerMargin.toString(),
        "speaker_confirmation_ms" to speakerConfirmationMs.toString(), "unknown_bridge_ms" to unknownBridgeMs.toString(),
        "label_lookahead_ms" to labelLookaheadMs.toString(), "alignment_tolerance_ms" to alignmentToleranceMs.toString(),
        "asr_window_seconds" to asrWindowSeconds.toString(), "asr_min_seconds" to asrMinSeconds.toString(),
        "asr_left_context_seconds" to asrLeftContextSeconds.toString(), "asr_right_context_seconds" to asrRightContextSeconds.toString(),
        "silence_duration_ms" to silenceDurationMs.toString(), "silence_amplitude" to silenceAmplitude.toString(),
        "queue_seconds" to queueSeconds.toString(), "capture_buffer_seconds" to captureBufferSeconds.toString(),
        "capture_block_ms" to captureBlockMs.toString(), "wake_recovery_ms" to wakeRecoveryMs.toString(),
        "wake_reopen_ms" to wakeReopenMs.toString(), "download_connect_seconds" to downloadConnectSeconds.toString(),
        "download_read_seconds" to downloadReadSeconds.toString(), "native_cache_frames" to nativeCacheFrames.toString(),
        "native_fifo_frames" to nativeFifoFrames.toString(), "native_update_frames" to nativeUpdateFrames.toString(),
        "diagnostics" to diagnostics.toString(),
        "strong_speaker_threshold" to strongSpeakerThreshold.toString(), "strong_speaker_margin" to strongSpeakerMargin.toString(),
        "strong_confirmation_ms" to strongConfirmationMs.toString(), "word_fallback_ms" to wordFallbackMs.toString()
    )

    /** Field-keyed messages let an editor keep invalid drafts without ever persisting them. */
    fun validationErrors(): Map<String, String> {
        val errors = fieldErrors(toMap()).toMutableMap()
        fun reject(keys: List<String>, message: String) { keys.forEach { errors[it] = message } }
        if (asrMinSeconds > asrWindowSeconds) reject(windowKeys, "Minimum window must not exceed maximum window")
        if (asrWindowSeconds + asrLeftContextSeconds + asrRightContextSeconds > 12.0) {
            reject(windowKeys, "Window plus left and right context must not exceed the fixed 12 s IPC limit")
        }
        if (nativeCacheFrames % 8 != 0) errors["native_cache_frames"] = "Cache must be a multiple of 8 frames (16–1024)"
        if (nativeUpdateFrames > nativeFifoFrames) {
            reject(cacheKeys, "Update frames must not exceed FIFO frames")
        }
        return errors
    }

    fun requireValid(): RuntimeOptions = apply {
        val errors = validationErrors()
        require(errors.isEmpty()) { errors.entries.joinToString("; ") { "${it.key}: ${it.value}" } }
    }

    /** All mutually dependent settings share a group, so resetting cannot leave half a constraint. */
    fun resetGroup(group: RuntimeOptionGroup): RuntimeOptions {
        val values = toMap().toMutableMap()
        fields.filter { it.group == group }.forEach { values[it.key] = defaults.getValue(it.key) }
        return decode(values).requireValid()
    }

    data class Draft(val options: RuntimeOptions?, val errors: Map<String, String>)

    companion object {
        private val defaults by lazy { RuntimeOptions().toMap() }
        private val windowKeys = listOf("asr_window_seconds", "asr_min_seconds", "asr_left_context_seconds", "asr_right_context_seconds")
        private val cacheKeys = listOf("native_cache_frames", "native_fifo_frames", "native_update_frames")

        /** Bounds cap memory/CPU exposure as well as rejecting malformed numeric input. */
        val fields: List<RuntimeOptionField> = buildList {
            fun integer(key: String, group: RuntimeOptionGroup, min: Long, max: Long) {
                add(RuntimeOptionField(key, group, RuntimeOptionKind.INTEGER, min.toDouble(), max.toDouble()))
            }
            fun decimal(key: String, group: RuntimeOptionGroup, min: Double, max: Double) {
                add(RuntimeOptionField(key, group, RuntimeOptionKind.DECIMAL, min, max))
            }
            listOf("worker_connect_seconds", "prepare_stall_seconds", "inference_stall_seconds", "absolute_operation_seconds")
                .forEach { integer(it, RuntimeOptionGroup.RECOVERY, 0, 86400) }
            listOf("asr_threads", "diarization_threads").forEach { integer(it, RuntimeOptionGroup.CPU, 0, 32) }
            add(RuntimeOptionField("diarization_mode", RuntimeOptionGroup.DIARIZATION, RuntimeOptionKind.CHOICE,
                choices = listOf("low_latency", "very_low_latency", "ultra_low_latency")))
            integer("diarization_batch", RuntimeOptionGroup.DIARIZATION, 1, 16)
            listOf("speaker_threshold", "speaker_margin").forEach { decimal(it, RuntimeOptionGroup.DIARIZATION, 0.0, 1.0) }
            listOf("speaker_confirmation_ms", "unknown_bridge_ms", "label_lookahead_ms", "alignment_tolerance_ms")
                .forEach { integer(it, RuntimeOptionGroup.DIARIZATION, 0, 10000) }
            listOf("asr_window_seconds", "asr_min_seconds").forEach { decimal(it, RuntimeOptionGroup.AUDIO, 0.001, 12.0) }
            listOf("asr_left_context_seconds", "asr_right_context_seconds").forEach { decimal(it, RuntimeOptionGroup.AUDIO, 0.0, 12.0) }
            integer("silence_duration_ms", RuntimeOptionGroup.AUDIO, 1, 60000)
            integer("silence_amplitude", RuntimeOptionGroup.AUDIO, 1, 32767)
            integer("queue_seconds", RuntimeOptionGroup.AUDIO, 1, 300)
            decimal("capture_buffer_seconds", RuntimeOptionGroup.CAPTURE, 0.05, 10.0)
            integer("capture_block_ms", RuntimeOptionGroup.CAPTURE, 1, 200)
            listOf("wake_recovery_ms", "wake_reopen_ms").forEach { integer(it, RuntimeOptionGroup.CAPTURE, 1, 60000) }
            listOf("download_connect_seconds", "download_read_seconds").forEach { integer(it, RuntimeOptionGroup.DOWNLOADS, 0, 86400) }
            listOf("native_cache_frames", "native_fifo_frames").forEach { integer(it, RuntimeOptionGroup.EXPERIMENTAL, 16, 1024) }
            integer("native_update_frames", RuntimeOptionGroup.EXPERIMENTAL, 1, 1024)
            add(RuntimeOptionField("diagnostics", RuntimeOptionGroup.EXPERIMENTAL, RuntimeOptionKind.BOOLEAN))
            listOf("strong_speaker_threshold", "strong_speaker_margin")
                .forEach { decimal(it, RuntimeOptionGroup.DIARIZATION, 0.0, 1.0) }
            integer("strong_confirmation_ms", RuntimeOptionGroup.DIARIZATION, 0, 10000)
            integer("word_fallback_ms", RuntimeOptionGroup.DIARIZATION, 10, 2000)
        }

        /**
         * Tolerant disk/IPC reads: default malformed/out-of-range fields individually, then
         * reset only an inconsistent dependency set. Unknown future keys are ignored. No
         * partially valid cache/window combination can escape into native code at startup.
         * User writes must instead use requireValid/parseDraft: never silently repair edits.
         */
        fun fromMap(values: Map<String, String>): RuntimeOptions {
            val normalized = defaults.toMutableMap()
            fields.forEach { field ->
                values[field.key]?.trim()?.takeIf { field.error(it) == null }?.let { normalized[field.key] = it }
            }
            val errors = decode(normalized).validationErrors()
            listOf(windowKeys, cacheKeys).forEach { keys ->
                if (keys.any { it in errors }) keys.forEach { normalized[it] = defaults.getValue(it) }
            }
            return decode(normalized)
        }

        /** Missing keys use defaults, but a present invalid value never does. */
        fun parseDraft(values: Map<String, String>): Draft {
            val complete = defaults + values.mapValues { it.value.trim() }
            val syntax = fieldErrors(complete)
            if (syntax.isNotEmpty()) return Draft(null, syntax)
            val options = decode(complete)
            val errors = options.validationErrors()
            return Draft(options.takeIf { errors.isEmpty() }, errors)
        }

        fun resolveThreads(requested: Int, available: Int = Runtime.getRuntime().availableProcessors()): Int {
            require(requested in 0..32) { "Thread count must be 0 (Auto) or 1–32" }
            return if (requested == 0) available.coerceIn(1, 4) else requested
        }

        private fun fieldErrors(values: Map<String, String>): Map<String, String> = buildMap {
            fields.forEach { field -> field.error(values.getValue(field.key))?.let { put(field.key, it) } }
        }

        // Called only after syntax/range checks; explicit conversions keep the public type
        // contract independent of Android, reflection, JSON libraries and locale formatting.
        private fun decode(v: Map<String, String>) = RuntimeOptions(
            workerConnectSeconds = v.getValue("worker_connect_seconds").toLong(),
            prepareStallSeconds = v.getValue("prepare_stall_seconds").toLong(),
            inferenceStallSeconds = v.getValue("inference_stall_seconds").toLong(),
            absoluteOperationSeconds = v.getValue("absolute_operation_seconds").toLong(),
            asrThreads = v.getValue("asr_threads").toInt(), diarizationThreads = v.getValue("diarization_threads").toInt(),
            diarizationMode = v.getValue("diarization_mode"), diarizationBatch = v.getValue("diarization_batch").toInt(),
            speakerThreshold = v.getValue("speaker_threshold").toFloat(), speakerMargin = v.getValue("speaker_margin").toFloat(),
            speakerConfirmationMs = v.getValue("speaker_confirmation_ms").toInt(), unknownBridgeMs = v.getValue("unknown_bridge_ms").toInt(),
            labelLookaheadMs = v.getValue("label_lookahead_ms").toInt(), alignmentToleranceMs = v.getValue("alignment_tolerance_ms").toInt(),
            asrWindowSeconds = v.getValue("asr_window_seconds").toDouble(), asrMinSeconds = v.getValue("asr_min_seconds").toDouble(),
            asrLeftContextSeconds = v.getValue("asr_left_context_seconds").toDouble(), asrRightContextSeconds = v.getValue("asr_right_context_seconds").toDouble(),
            silenceDurationMs = v.getValue("silence_duration_ms").toInt(), silenceAmplitude = v.getValue("silence_amplitude").toInt(),
            queueSeconds = v.getValue("queue_seconds").toInt(), captureBufferSeconds = v.getValue("capture_buffer_seconds").toDouble(),
            captureBlockMs = v.getValue("capture_block_ms").toInt(), wakeRecoveryMs = v.getValue("wake_recovery_ms").toInt(),
            wakeReopenMs = v.getValue("wake_reopen_ms").toInt(), downloadConnectSeconds = v.getValue("download_connect_seconds").toLong(),
            downloadReadSeconds = v.getValue("download_read_seconds").toLong(), nativeCacheFrames = v.getValue("native_cache_frames").toInt(),
            nativeFifoFrames = v.getValue("native_fifo_frames").toInt(), nativeUpdateFrames = v.getValue("native_update_frames").toInt(),
            diagnostics = v.getValue("diagnostics").toBooleanStrict(),
            strongSpeakerThreshold = v.getValue("strong_speaker_threshold").toFloat(),
            strongSpeakerMargin = v.getValue("strong_speaker_margin").toFloat(),
            strongConfirmationMs = v.getValue("strong_confirmation_ms").toInt(),
            wordFallbackMs = v.getValue("word_fallback_ms").toInt()
        )
    }
}

enum class RuntimeOptionGroup { RECOVERY, CPU, DIARIZATION, AUDIO, CAPTURE, DOWNLOADS, EXPERIMENTAL }
enum class RuntimeOptionKind { INTEGER, DECIMAL, CHOICE, BOOLEAN }

data class RuntimeOptionField(
    val key: String,
    val group: RuntimeOptionGroup,
    val kind: RuntimeOptionKind,
    val minimum: Double = 0.0,
    val maximum: Double = 0.0,
    val choices: List<String> = emptyList()
) {
    fun error(value: String): String? = when (kind) {
        RuntimeOptionKind.BOOLEAN -> if (value.toBooleanStrictOrNull() == null) "Choose true or false" else null
        RuntimeOptionKind.CHOICE -> if (value !in choices) "Choose ${choices.joinToString()}" else null
        else -> {
            val number = if (kind == RuntimeOptionKind.INTEGER) value.toLongOrNull()?.toDouble() else value.toDoubleOrNull()
            when {
                number == null || !number.isFinite() -> "Enter a finite ${if (kind == RuntimeOptionKind.INTEGER) "whole number" else "number"}"
                number < minimum || number > maximum -> "Value must be between $minimum and $maximum"
                else -> null
            }
        }
    }
}
