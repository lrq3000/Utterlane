package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class ResultSpanCodecTest {
    @Test fun keepsSpeakerMetadataAndExactTextIncludingAnEmptyFinalResult() {
        assertEquals(listOf(SpeechSpan(" first", 0), SpeechSpan("  tail!", -1)),
            ResultSpanCodec.fromArrays(arrayOf(" first", "  tail!"), intArrayOf(0, -1)))
        assertTrue(ResultSpanCodec.fromArrays(emptyArray(), intArrayOf()).isEmpty())
    }

    @Test fun rejectsMalformedMetadataAndResultsOutsideTheExistingIpcBudget() {
        for ((texts, speakers) in listOf(
            arrayOf("tail") to intArrayOf(),
            arrayOf("tail") to intArrayOf(-2),
            arrayOf("tail") to intArrayOf(8),
            Array(8193) { "x" } to IntArray(8193),
            arrayOf("x".repeat(128001)) to intArrayOf(0)
        )) assertThrows(IllegalStateException::class.java) { ResultSpanCodec.fromArrays(texts, speakers) }
        assertEquals(8192, ResultSpanCodec.fromArrays(Array(8192) { "x" }, IntArray(8192) { 7 }).size)
        assertEquals(128000, ResultSpanCodec.fromArrays(arrayOf("x".repeat(128000)), intArrayOf(-1)).single().text.length)
    }
}
