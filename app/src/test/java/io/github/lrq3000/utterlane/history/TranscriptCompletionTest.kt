package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptCompletionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun interruptedCopyIsNotCompletedByAnotherAttemptOnSameAudio() {
        val root = temporary.newFolder("history")
        val history = TranscriptHistory(root)
        val partial = TranscriptStore(temporary.newFile("first.txt"))
        val retry = TranscriptStore(temporary.newFile("second.txt"))
        try {
            partial.beginTranscription(TranscriptSource(audioId = "audio"))
            assertEquals("INCOMPLETE", TranscriptSource.read(partial.file).completion.name)
            partial.append("First words")
            val interrupted = history.save(partial.file, "Model", audioId = "audio")
            retry.beginTranscription(TranscriptSource(audioId = "audio"))
            retry.append("First words and the end")
            retry.markComplete()
            // A stale owner publishing a linkage update may not clear the terminal bit.
            retry.attachSource(TranscriptSource(audioId = "audio", recovered = true))
            val finished = history.save(retry.file, "Model", audioId = "audio")

            assertEquals("First words", interrupted.file.readText())
            assertEquals("INCOMPLETE", TranscriptSource.read(partial.file).completion.name)
            val reopened = TranscriptHistory(root)
            assertEquals("INCOMPLETE", reopened.find(interrupted.id)?.completion?.name)
            assertEquals("COMPLETE", reopened.find(finished.id)?.completion?.name)
        } finally {
            partial.keepForRecovery()
            retry.keepForRecovery()
        }
    }

    @Test fun untrackedLegacyTextHasNoInferredStatus() {
        val legacy = temporary.newFile("legacy.txt").apply { writeText("Existing result") }
        val history = TranscriptHistory(File(temporary.root, "history"))
        val saved = history.save(legacy, "Model")
        assertEquals("UNKNOWN", TranscriptSource.read(legacy).completion.name)
        assertEquals("UNKNOWN", saved.completion.name)
    }
}
