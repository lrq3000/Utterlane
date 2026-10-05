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
        assertTrue(text.contains("Unknown speaker: greetings"))
        assertEquals("greetings greetings", stripLabels(text))
        assertFalse(text.contains("WRONG"))
    }
    @Test fun unknownIsExplicitAndEmptyAudioHasNoLabel() {
        val formatter = SpeakerText(StreamingCorrections(emptyList())) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
        assertEquals(emptyList<String>(), formatter.accept(emptyList()))
        assertEquals("", formatter.finish())
        assertEquals(listOf("Unknown speaker: text"), formatter.accept(listOf(SpeechSpan("text", -1))))
    }

    private fun stripLabels(text: String) = text.replace(Regex("(?:Speaker \\d+|Unknown speaker): ?"), "")
        .replace(Regex("\\s+"), " ").trim()

    @Test fun correctionsAcrossRealAndFalseSpeakerChangesMatchOffWordsAcrossWindows() {
        val rules = listOf(
            DictionaryManager.ReplacementRule("New York", "NYC"),
            DictionaryManager.ReplacementRule("foo bar", "expanded phrase"),
            DictionaryManager.ReplacementRule("delete this", "")
        )
        val windows = listOf(
            listOf(SpeechSpan("visit New", 0)),
            listOf(SpeechSpan("York and foo", 1), SpeechSpan("bar then delete", 0)),
            listOf(SpeechSpan("this été.", 1), SpeechSpan("Hein?", 0), SpeechSpan("Yes!", 1))
        )
        val off = StreamingCorrections(rules)
        val on = SpeakerText(StreamingCorrections(rules)) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
        val baseline = windows.map { off.accept(it.joinToString(" ") { span -> span.text }) } + off.finish()
        val labeled = windows.flatMap { on.accept(it) } + on.finish()
        assertEquals(stripLabels(baseline.joinToString(" ")), stripLabels(labeled.joinToString(" ")))
        assertEquals("visit NYC and expanded phrase then été. Hein? Yes!", stripLabels(labeled.joinToString(" ")))
    }

    @Test fun rawSpacingIsKeptWhileMatchingRulesAcrossLabels() {
        val formatter = SpeakerText(StreamingCorrections(listOf(
            DictionaryManager.ReplacementRule("first  second", "together")
        ))) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
        val text = (formatter.accept(listOf(SpeechSpan("first", 0), SpeechSpan("  second", 1))) + formatter.finish()).joinToString(" ")
        assertEquals("together", stripLabels(text))
    }

    @Test fun repeatedLabelsDoNotAccumulateCorrectionHistoryForTheWholeRecording() {
        val formatter = SpeakerText(StreamingCorrections(listOf(
            DictionaryManager.ReplacementRule("hello world", "greetings")
        ))) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
        var emittedWords = 0
        repeat(10000) { index ->
            val result = formatter.accept(listOf(SpeechSpan(if (index % 2 == 0) "hello" else "world", index % 2)))
            val words = stripLabels(result.joinToString(" "))
            if (words.isNotEmpty()) emittedWords += words.split(' ').size
            assertTrue("Correction buffering must be bounded", emittedWords >= index / 2 - 2)
        }
        emittedWords += stripLabels(formatter.finish()).split(' ').size
        assertEquals(5000, emittedWords)
    }
}
