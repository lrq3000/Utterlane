package io.github.lrq3000.utterlane.history

enum class ImportRetentionChoice { KEEP, DISCARD }
enum class ImportRetentionCategory { DURATION, SIZE }

data class ImportRetentionPreferences(
    val audioHistoryEnabled: Boolean = true,
    val durationChoice: ImportRetentionChoice? = null,
    val sizeChoice: ImportRetentionChoice? = null
)

sealed interface ImportRetentionDecision {
    data class Use(val choice: ImportRetentionChoice) : ImportRetentionDecision
    data class Ask(val category: ImportRetentionCategory) : ImportRetentionDecision
}

object ImportRetentionPolicy {
    const val DURATION_THRESHOLD_MS = 600000L
    const val SIZE_THRESHOLD_BYTES = 52428800L

    fun decide(durationMs: Long?, sourceBytes: Long?, preferences: ImportRetentionPreferences): ImportRetentionDecision {
        if (!preferences.audioHistoryEnabled) return ImportRetentionDecision.Use(ImportRetentionChoice.DISCARD)
        // Duration wins even if both thresholds are exceeded. A remembered answer
        // resolves this one question; it must never fall through to a second one.
        val category = when {
            durationMs != null && durationMs > DURATION_THRESHOLD_MS -> ImportRetentionCategory.DURATION
            sourceBytes != null && sourceBytes > SIZE_THRESHOLD_BYTES -> ImportRetentionCategory.SIZE
            else -> return ImportRetentionDecision.Use(ImportRetentionChoice.KEEP)
        }
        val remembered = when (category) {
            ImportRetentionCategory.DURATION -> preferences.durationChoice
            ImportRetentionCategory.SIZE -> preferences.sizeChoice
        }
        return remembered?.let(ImportRetentionDecision::Use) ?: ImportRetentionDecision.Ask(category)
    }
}

/** Null means the provider/track did not supply this fact. Container MIME and
 * audio track MIME are independent; a filename is never evidence of a codec. */
data class AudioImportMetadata(
    val displayName: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val sourceBytes: Long? = null,
    val trackMimeType: String? = null,
    val isCompressed: Boolean? = null
)
