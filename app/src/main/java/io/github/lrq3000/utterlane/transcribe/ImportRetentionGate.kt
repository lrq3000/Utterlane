package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.CompletableDeferred

data class ImportRetentionRequest(val category: ImportRetentionCategory, val metadata: AudioImportMetadata)

/** One source inspection, at most one question, then authorization to copy. */
internal class ImportRetentionGate(
    private val question: (ImportRetentionRequest?) -> Unit,
    private val remember: suspend (ImportRetentionCategory, ImportRetentionChoice) -> Unit
) {
    private data class Answer(val choice: ImportRetentionChoice, val remember: Boolean)
    private var pending: CompletableDeferred<Answer?>? = null

    suspend fun resolve(metadata: AudioImportMetadata, preferences: ImportRetentionPreferences): ImportRetentionChoice? {
        val decision = ImportRetentionPolicy.decide(metadata.durationMs, metadata.sourceBytes, preferences)
        if (decision is ImportRetentionDecision.Use) return decision.choice
        check(pending == null) { "An import question is already open" }
        val category = (decision as ImportRetentionDecision.Ask).category
        val answer = CompletableDeferred<Answer?>()
        pending = answer
        try {
            question(ImportRetentionRequest(category, metadata))
            val chosen = answer.await() ?: return null
            if (chosen.remember) remember(category, chosen.choice)
            return chosen.choice
        } finally {
            pending = null
            question(null)
        }
    }

    fun answer(choice: ImportRetentionChoice, remember: Boolean) { pending?.complete(Answer(choice, remember)) }
    fun cancel() { pending?.complete(null) }
}
