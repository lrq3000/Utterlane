package io.github.lrq3000.utterlane.home

import android.content.Context
import io.github.lrq3000.utterlane.asr.CaptureSnapshot
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import io.github.lrq3000.utterlane.transcribe.DialogInput

/** Only stable local identifiers are journaled; transcript/audio contents stay on disk. */
internal class HomeJournal(context: Context) {
    private val prefs = context.getSharedPreferences("home_workspace", Context.MODE_PRIVATE)
    private var previous: DialogInput? = null

    fun capture(audioId: String?, store: TranscriptStore?, snapshot: CaptureSnapshot?) {
        if (audioId == null && store == null) return
        write(DialogInput(audioId = audioId, transcriptPath = store?.file?.absolutePath,
            modelName = snapshot?.modelName.orEmpty()))
    }
    fun write(input: DialogInput) {
        if (input == previous) return
        previous = input
        val metadata = input.metadata
        prefs.edit().clear().putString("audio", input.audioId).putString("text", input.transcriptId)
            .putString("path", input.transcriptPath).putString("model", input.modelName).putString("model_id", input.modelId)
            .putLong("created", metadata?.created ?: 0).putLong("duration", metadata?.durationMs ?: 0)
            .putBoolean("has_created", metadata?.created != null)
            .putBoolean("speakers", metadata?.speakerLabels ?: false).apply()
    }
    fun restore(): DialogInput? {
        val input = DialogInput(audioId = prefs.getString("audio", null), transcriptId = prefs.getString("text", null),
            transcriptPath = prefs.getString("path", null), transcriptOrigin = false,
            modelName = prefs.getString("model", "").orEmpty(), modelId = prefs.getString("model_id", null),
            // Old journals used zero as unknown. New journals distinguish real
            // epoch zero (and earlier timestamps) from absent source chronology.
            metadata = TranscriptMetadata(prefs.getLong("created", 0).takeIf {
                prefs.getBoolean("has_created", it > 0)
            },
                prefs.getLong("duration", 0), prefs.getBoolean("speakers", false)))
        return input.takeIf { it.audioId != null || it.transcriptId != null || it.transcriptPath != null }
    }
    fun clear() { previous = null; prefs.edit().clear().apply() }
}
