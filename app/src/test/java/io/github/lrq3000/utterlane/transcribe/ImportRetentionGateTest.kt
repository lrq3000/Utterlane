package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ImportRetentionGateTest {
    private val metadata = AudioImportMetadata(durationMs = 600001, sourceBytes = Long.MAX_VALUE)

    @Test fun importWaitsBeforeCopyAndRemembersOnlyTheAnsweredCategory() = runBlocking {
        val questions = mutableListOf<ImportRetentionRequest?>()
        val remembered = mutableListOf<Pair<ImportRetentionCategory, ImportRetentionChoice>>()
        val gate = ImportRetentionGate(questions::add) { category, choice -> remembered.add(category to choice) }
        var copied = false
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            gate.resolve(metadata, ImportRetentionPreferences())?.also { copied = true }
        }
        assertFalse(copied)
        assertEquals(ImportRetentionCategory.DURATION, questions.single()!!.category)
        gate.answer(ImportRetentionChoice.DISCARD, remember = true)
        gate.answer(ImportRetentionChoice.KEEP, remember = true) // A double tap cannot overwrite the first answer.
        assertEquals(ImportRetentionChoice.DISCARD, task.await())
        assertTrue(copied)
        assertEquals(listOf(ImportRetentionCategory.DURATION to ImportRetentionChoice.DISCARD), remembered)
        assertNull(questions.last())
        assertEquals(1, questions.count { it != null })
    }

    @Test fun rejectionAndCoroutineCancellationNeverAuthorizeCopyOrRememberAnAnswer() = runBlocking {
        for (cancelJob in listOf(false, true)) {
            var question: ImportRetentionRequest? = null
            val gate = ImportRetentionGate({ question = it }) { _, _ -> fail("Nothing was chosen") }
            var copied = false
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                gate.resolve(metadata, ImportRetentionPreferences())?.also { copied = true }
            }
            assertNotNull(question)
            if (cancelJob) task.cancelAndJoin() else { gate.cancel(); assertNull(task.await()) }
            assertFalse(copied)
            assertNull(question)
        }
    }

    @Test fun uncheckedAnswerAndRememberedOrDisabledPolicyDoNotWritePreferences() = runBlocking {
        var question: ImportRetentionRequest? = null
        val gate = ImportRetentionGate({ question = it }) { _, _ -> fail("Do not remember") }
        val task = async(start = CoroutineStart.UNDISPATCHED) { gate.resolve(metadata, ImportRetentionPreferences()) }
        gate.answer(ImportRetentionChoice.KEEP, remember = false)
        assertEquals(ImportRetentionChoice.KEEP, task.await())
        assertEquals(ImportRetentionChoice.DISCARD, gate.resolve(metadata, ImportRetentionPreferences(false)))
        assertNull(question)
        assertEquals(ImportRetentionChoice.DISCARD,
            gate.resolve(metadata, ImportRetentionPreferences(durationChoice = ImportRetentionChoice.DISCARD)))
        assertNull(question)
    }
}
