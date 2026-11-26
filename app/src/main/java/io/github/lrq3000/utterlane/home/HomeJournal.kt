package io.github.lrq3000.utterlane.home

import android.content.Context
import io.github.lrq3000.utterlane.asr.CaptureSnapshot
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import io.github.lrq3000.utterlane.transcribe.DialogInput
import io.github.lrq3000.utterlane.transcribe.FileTranscriptionOptions

/** Only stable local identifiers are journaled; transcript/audio contents stay on disk. */
internal class HomeJournal(context: Context) {
    private val prefs = context.getSharedPreferences("home_workspace", Context.MODE_PRIVATE)
    private var previous: DialogInput? = null

    fun capture(audioId: String?, store: TranscriptStore?, snapshot: CaptureSnapshot?, speakerLabels: Boolean = false, queueId: String? = null) {
        if (audioId == null && store == null) return
        write(DialogInput(audioId = audioId, transcriptPath = store?.file?.absolutePath,
            modelName = snapshot?.modelName.orEmpty(),
            // Audio's final metadata may not be written before process loss.
            // Checkpoint the committed-text flag, never the requested setting.
            metadata = TranscriptMetadata(speakerLabels = speakerLabels), queueId = queueId))
    }
    fun write(input: DialogInput) {
        if (input == previous) return
        previous = input
        val metadata = input.metadata
        prefs.edit().clear().putString("audio", input.audioId).putString("text", input.transcriptId).putString("queue", input.queueId)
            .putString("path", input.transcriptPath).putString("model", input.modelName).putString("model_id", input.modelId)
            .putLong("created", metadata?.created ?: 0).putLong("duration", metadata?.durationMs ?: 0)
            .putBoolean("has_created", metadata?.created != null)
            .putBoolean("speakers", metadata?.speakerLabels ?: false)
            .putBoolean("recovered", metadata?.recovered ?: false).apply {
                input.fileOptions?.let { putBoolean("file_speaker_labels", it.speakerLabels) }
            }.apply()
    }
    fun restore(): DialogInput? {
        val input = DialogInput(audioId = prefs.getString("audio", null), transcriptId = prefs.getString("text", null), queueId = prefs.getString("queue", null),
            transcriptPath = prefs.getString("path", null), transcriptOrigin = false,
            modelName = prefs.getString("model", "").orEmpty(), modelId = prefs.getString("model_id", null),
            // Old journals used zero as unknown. New journals distinguish real
            // epoch zero (and earlier timestamps) from absent source chronology.
            metadata = TranscriptMetadata(prefs.getLong("created", 0).takeIf {
                prefs.getBoolean("has_created", it > 0)
            },
                prefs.getLong("duration", 0), prefs.getBoolean("speakers", false), prefs.getBoolean("recovered", false)),
            fileOptions = if (prefs.contains("file_speaker_labels")) FileTranscriptionOptions(prefs.getBoolean("file_speaker_labels", false)) else null)
        return input.takeIf { it.audioId != null || it.transcriptId != null || it.transcriptPath != null }
    }
    fun clear() { previous = null; prefs.edit().clear().apply() }
}
