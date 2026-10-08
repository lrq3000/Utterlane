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

    @Test fun orderedReplacementRulesCannotChangeWordsWhenLabelLookaheadChangesDeliveryBatches() {
        val rules = listOf(DictionaryManager.ReplacementRule("a", "hello"),
            DictionaryManager.ReplacementRule("hello b", "combined"))
        val off = StreamingCorrections(rules)
        val on = SpeakerText(StreamingCorrections(rules)) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
        val baseline = listOf(off.accept("a b c d e"), off.accept("f g h"), off.finish()).joinToString(" ")
        // Native lookahead can deliver these same words in a single later batch.
        val labeled = (on.accept(listOf(SpeechSpan("a", 0), SpeechSpan("b c d e f g h", 1))) + on.finish()).joinToString(" ")
        assertEquals(stripLabels(baseline), stripLabels(labeled))
        assertEquals("combined c d e f g h", stripLabels(labeled))
    }

    @Test fun orderedExpansionAndDeletionMatchWholeTextRulesForEveryWordPartition() {
        val rules = listOf(DictionaryManager.ReplacementRule("red blue", "blue green"),
            DictionaryManager.ReplacementRule("green tail", "fused"),
            DictionaryManager.ReplacementRule("drop", ""), DictionaryManager.ReplacementRule("NY", "New York"),
            DictionaryManager.ReplacementRule("New York city", "NYC"))
        val raw = "red blue tail drop NY city red blue tail"
        var expected = raw
        for (rule in rules) expected = Regex("\\b${Regex.escape(rule.from)}\\b", RegexOption.IGNORE_CASE)
            .replace(expected) { rule.to }
        val words = raw.split(' ')
        for (size in 1..words.size) {
            val corrections = StreamingCorrections(rules)
            val chunks = words.chunked(size).map { corrections.accept(it.joinToString(" ")) } + corrections.finish()
            assertEquals("Word partition $size", stripLabels(expected), stripLabels(chunks.joinToString(" ")))
        }
    }

    @Test fun crossSpeakerCorrectionsKeepLiteralPunctuationWithTheCorrectedWord() {
        val rules = listOf(DictionaryManager.ReplacementRule("hello world", "greetings"))
        for (suffix in listOf("!", "?!", "…»", " !")) for (split in listOf(false, true)) {
            val off = StreamingCorrections(rules)
            val formatter = formatterFor(rules)
            val spans = listOf(SpeechSpan("hello", 0), SpeechSpan(" world$suffix", 1))
            val batches = if (split) spans.map { listOf(it) } else listOf(spans)
            val baseline = (batches.map { off.accept(it.joinToString("") { span -> span.text }) } + off.finish())
                .filter { it.isNotBlank() }.joinToString(" ")
            val labeled = (batches.flatMap { formatter.accept(it) } + formatter.finish())
                .filter { it.isNotBlank() }.joinToString(" ")
            assertEquals("greetings$suffix", baseline)
            assertEquals("suffix=$suffix split=$split", "Unknown speaker: $baseline", labeled)
        }
    }

    @Test fun trailingPunctuationPrecedesTheNextGenuineSpeakerHeader() {
        val formatter = formatterFor(listOf(DictionaryManager.ReplacementRule("hello world", "greetings")))
        val text = (formatter.accept(listOf(SpeechSpan("hello", 0), SpeechSpan(" world! Next.", 1))) + formatter.finish())
            .filter { it.isNotBlank() }.joinToString(" ")
        assertEquals("greetings! Next.", stripLabels(text))
        assertTrue(text.startsWith("Unknown speaker: greetings!"))
        assertTrue(text.contains("Speaker 2: Next."))
        assertFalse(text.contains("Speaker 2: !"))
    }

    @Test fun punctuationAloneCannotStartATurnOrChangeItsSpeaker() {
        val formatter = formatterFor()
        assertEquals(listOf("!"), formatter.accept(listOf(SpeechSpan("!", 1))))
        assertEquals(listOf("Unknown speaker: greetings"), formatter.accept(listOf(SpeechSpan("greetings", -1))))
        assertEquals(listOf("?!"), formatter.accept(listOf(SpeechSpan("?!", 1))))
        assertEquals(listOf("again"), formatter.accept(listOf(SpeechSpan("again", -1))))
    }

    @Test fun eachEmissionReportsOnlyTheHeadersItActuallyContains() {
        val formatter = SpeakerText(StreamingCorrections(emptyList())) { "Voix α" }
        assertEquals(SpeakerText.Emission("!", false), formatter.acceptEmission(listOf(SpeechSpan("!", 0))))
        assertEquals(SpeakerText.Emission("Voix α: words", true), formatter.acceptEmission(listOf(SpeechSpan("words", 0))))
        assertEquals(SpeakerText.Emission("?!", false), formatter.acceptEmission(listOf(SpeechSpan("?!", 1))))
        assertEquals(SpeakerText.Emission("again", false), formatter.acceptEmission(listOf(SpeechSpan("again", 0))))
        assertEquals(SpeakerText.Emission("\nVoix α: next", true), formatter.acceptEmission(listOf(SpeechSpan("next", 1))))
        assertEquals(SpeakerText.Emission("", false), formatter.finishEmission())
    }

    @Test fun correctionTailReportsItsFirstHeaderOnlyWhenFlushed() {
        val formatter = formatterFor(listOf(DictionaryManager.ReplacementRule("words", "words")))
        assertEquals(SpeakerText.Emission("!", false), formatter.acceptEmission(listOf(SpeechSpan("! words", 0))))
        assertEquals(SpeakerText.Emission("Speaker 1: words", true), formatter.finishEmission())
    }

    @Test fun incomingOpeningPunctuationStaysWithItsWordAndSpeaker() {
        val phrases = listOf("¿Qué tal?", "¡Hola!", "“Quoted.”", "‘Quoted.’", "«Bonjour.»",
            "\"Quoted.\"", "'Quoted.'", "(aside)", "[aside]", "{aside}", "「文」", "(“Quoted.”)")
        for (phrase in phrases) for (split in listOf(false, true)) {
            val formatter = formatterFor()
            val spans = listOf(SpeechSpan("Hola.", 0), SpeechSpan(" $phrase", 1))
            val batches = if (split) spans.map { listOf(it) } else listOf(spans)
            val text = (batches.flatMap { formatter.accept(it) } + formatter.finish()).joinToString("")
            assertEquals("phrase=$phrase split=$split", "Speaker 1: Hola.\nSpeaker 2: $phrase", text)
        }
    }

    @Test fun replacementTrailingPunctuationAndIncomingOpeningPrefixStayOnTheirOwnSides() {
        val cases = listOf("!" to "¿Qué tal?", "!" to "¡Hola!", "!" to "“Next.”",
            "!" to "\"Next.\"", "!)" to "[Next.]", "!\"" to "Next.", "!”" to "«Next.»")
        for ((trailing, incoming) in cases) {
            val formatter = formatterFor(listOf(DictionaryManager.ReplacementRule("hello world", "greetings")))
            val text = (formatter.accept(listOf(SpeechSpan("hello", 0),
                SpeechSpan(" world$trailing $incoming", 1))) + formatter.finish()).joinToString("")
            assertEquals("trailing=$trailing incoming=$incoming",
                "Unknown speaker: greetings$trailing\nSpeaker 2: $incoming", text)
            assertEquals("greetings$trailing $incoming", stripLabels(text))
        }
    }

    private fun formatterFor(rules: List<DictionaryManager.ReplacementRule> = emptyList()) =
        SpeakerText(StreamingCorrections(rules)) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
}
