package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class SpeakerTextTest {
    @Test fun labelsAppearOnlyAtChangesAndCorrectionsCannotRewriteThem() {
        val formatter = SpeakerText(StreamingCorrections(listOf(
            DictionaryManager.ReplacementRule("Speaker", "WRONG", false),
            DictionaryManager.ReplacementRule("hello world", "greetings", false)
        ))) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
        val out = mutableListOf<String>()
        out += formatter.accept(listOf(SpeechSpan("hello", 0)))
        out += formatter.accept(listOf(SpeechSpan("world", 0), SpeechSpan("hello", 1)))
        out += formatter.accept(listOf(SpeechSpan("world", 0)))
        out += formatter.finish()
        val text = out.filter { it.isNotBlank() }.joinToString(" ")
        assertTrue(text.contains("Speaker 1: greetings"))
        assertTrue(text.contains("Speaker 2: hello"))
        assertTrue(text.contains("Speaker 1: world"))
        assertFalse(text.contains("WRONG"))
    }
    @Test fun unknownIsExplicitAndEmptyAudioHasNoLabel() {
        val formatter = SpeakerText(StreamingCorrections(emptyList())) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
        assertEquals(emptyList<String>(), formatter.accept(emptyList()))
        assertEquals("", formatter.finish())
        assertEquals(listOf("Unknown speaker: text"), formatter.accept(listOf(SpeechSpan("text", -1))))
    }
}
