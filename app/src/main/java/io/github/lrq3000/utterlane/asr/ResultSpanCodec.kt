package io.github.lrq3000.utterlane.asr

import android.os.Bundle

/** Shared wire contract for both ordinary speaker windows and the EOF drain. */
internal object ResultSpanCodec {
    fun toBundle(spans: List<SpeechSpan>): Bundle {
        val texts = spans.map { it.text }.toTypedArray()
        val speakers = spans.map { it.speaker }.toIntArray()
        validate(texts, speakers)
        return Bundle().apply { putStringArray("texts", texts); putIntArray("speakers", speakers) }
    }

    fun fromBundle(data: Bundle): List<SpeechSpan> = fromArrays(
        requireNotNull(data.getStringArray("texts")), requireNotNull(data.getIntArray("speakers")))

    fun fromArrays(texts: Array<String>, speakers: IntArray): List<SpeechSpan> {
        validate(texts, speakers)
        return texts.indices.map { SpeechSpan(texts[it], speakers[it]) }
    }

    private fun validate(texts: Array<String>, speakers: IntArray) {
        check(texts.size == speakers.size && speakers.all { it in -1..7 }) { "Invalid speaker result metadata" }
        check(texts.size <= 8192 && texts.sumOf { it.length.toLong() } <= 128000) { "Speaker result exceeds IPC budget" }
    }
}
