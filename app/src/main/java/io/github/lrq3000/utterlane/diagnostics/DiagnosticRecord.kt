package io.github.lrq3000.utterlane.diagnostics

import io.github.lrq3000.utterlane.asr.RecognitionStatus
import io.github.lrq3000.utterlane.settings.RuntimeOptions

/** Build/device identifiers only: never a serial, account, model filename, URI or user name. */
internal data class DiagnosticEnvironment(
    val appVersion: String = "unknown",
    val appVersionCode: Long = 0,
    val buildType: String = "unknown",
    val androidSdk: Int = 0,
    val manufacturer: String = "unknown",
    val deviceModel: String = "unknown",
    val abi: String = "unknown"
) {
    fun fields(): Map<String, Any> = mapOf(
        "app_version" to identifier(appVersion), "app_version_code" to appVersionCode,
        "build_type" to identifier(buildType), "android_sdk" to androidSdk,
        "manufacturer" to identifier(manufacturer), "device_model" to identifier(deviceModel), "abi" to identifier(abi),
        // No runtime version IPC exists. These are the reproducible source pins, not
        // a claim that the worker has successfully loaded/probed a particular library.
        "native_version_source" to "build_pins_not_runtime_probe",
        "crispasr_revision" to "966561aa596cfc653aa0e9885d44117fad9cca35",
        "ggml_revision" to "2f5a80d258c46e6ac8eee95f1328c0f58376d7ee",
        "transcribe_revision" to "ba949120d60f29daaaa13eec65b9c28c2c2112a6",
        "sherpa_version" to "1.12.23"
    )

    private fun identifier(value: String) = value.take(96).filter { it.isLetterOrDigit() || it in " ._+-" }
}

/** The queue can contain only these bounded, content-free values, never PCM/UI snapshots. */
internal sealed class DiagnosticRecord(val options: RuntimeOptions) {
    // `options` owns consent. A shared worker may still use an older configuration;
    // keep it separately rather than replacing its diagnostics bit in the export.
    open val configuration: RuntimeOptions get() = options
    class Activity(options: RuntimeOptions, private val status: RecognitionStatus, private val operation: Long,
        override val configuration: RuntimeOptions = options) : DiagnosticRecord(options) {
        override fun fields(): Map<String, Any> = mapOf(
            "kind" to "activity", "operation_id" to operation, "request_id" to status.requestId,
            "stage" to status.scope, "state" to status.stage.name.lowercase(), "active" to status.active,
            "opaque" to status.opaque, "active_elapsed_ms" to status.elapsedMillis,
            "since_progress_ms" to status.sinceProgressMillis, "completed_units" to status.completedUnits
        )
    }
    class Capture(options: RuntimeOptions, private val phase: String, private val captured: Long, private val processed: Long,
        private val captureId: Long = 0) : DiagnosticRecord(options) {
        init { require(phase in PHASES && captured >= 0 && processed in 0..captured) }
        override fun fields(): Map<String, Any> = mapOf(
            "kind" to "capture", "capture_id" to captureId, "phase" to phase,
            "captured_samples" to captured, "processed_samples" to processed,
            "sample_rate_hz" to 16000, "backlog_ms" to (captured - processed) / 16
        )
    }
    protected abstract fun fields(): Map<String, Any>

    fun encode(environment: DiagnosticEnvironment, runId: String, timestamp: Long, dropped: Long): ByteArray {
        val values = linkedMapOf<String, Any>("schema_version" to 1, "run_id" to runId,
            "monotonic_ms" to timestamp, "dropped_records" to dropped)
        values.putAll(fields())
        // Repeat the small immutable configuration with each sample. Rotation/export
        // can then never detach metrics from the options actually used by that operation.
        values["options"] = configuration.toMap()
        values["options_scope"] = if (this is Activity) "worker_configuration" else "capture_preferences"
        if (this is Activity) {
            // ASR threads/recovery belong to the shared worker, while diarization and
            // diagnostic consent belong to this session's immutable starting snapshot.
            values["operation_options"] = options.toMap()
            values["effective_asr_threads"] = RuntimeOptions.resolveThreads(configuration.asrThreads)
            values["effective_diarization_threads"] = RuntimeOptions.resolveThreads(options.diarizationThreads)
        }
        values["environment"] = environment.fields()
        return (json(values) + "\n").toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val PHASES = setOf("loading", "capturing", "stopping", "processing", "complete", "failed", "cancelled")
        // Android's JSONObject is a stub in local JVM tests. Explicit encoding also
        // makes the small schema auditable: there is no reflection/object serialization.
        private fun json(value: Any): String = when (value) {
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key as String) + ":" + json(it.value!!) }
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
            is Number, is Boolean -> value.toString()
            else -> error("Unsupported diagnostic field")
        }
    }
}
