package com.translander.asr

import android.util.Log

/** File and live sources share ownership, corrections and append-only result delivery. */
class TranscriptionSession(
    val store: TranscriptStore,
    private val corrections: StreamingCorrections,
    private val onSegment: suspend (String) -> Unit,
    decode: suspend (AudioWindow) -> String
) {
    private val segmenter = AudioSegmenter { window ->
        val text = decode(window)
        emit(corrections.accept(text))
        Log.i("TranscriptionSession", "Segment ${window.ownedStart}..${window.ownedEnd}; input=${window.samples.size}; completed=${store.segments}")
    }
    private var finished = false
    suspend fun accept(samples: ShortArray) = segmenter.accept(samples)
    suspend fun finish() {
        if (finished) return
        segmenter.finish()
        emit(corrections.finish())
        finished = true
    }
    private suspend fun emit(text: String) {
        if (text.isBlank()) return
        store.append(text)
        onSegment(text.trim())
    }
}

/** Raw trailing words are retained, so multi-word corrections can span audio windows. */
class StreamingCorrections(rules: List<DictionaryManager.ReplacementRule>) {
    private val compiled = rules.map {
        Pair(Regex("\\b${Regex.escape(it.from)}\\b", if (it.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)), it.to)
    }
    private val hold = rules.maxOfOrNull { it.from.length } ?: 0
    private var pending = ""

    fun accept(text: String): String {
        if (text.isNotBlank()) pending += (if (pending.isEmpty()) "" else " ") + text.trim()
        if (hold == 0) return finish()
        val limit = (pending.length - hold - 1).coerceAtLeast(0)
        var cut = pending.lastIndexOf(' ', limit).coerceAtLeast(0)
        for ((pattern, _) in compiled) {
            for (match in pattern.findAll(pending)) {
                if (match.range.first < cut && match.range.last >= cut) cut = match.range.first
            }
        }
        val prefix = pending.take(cut)
        pending = pending.drop(cut).trimStart()
        return correct(prefix)
    }
    fun finish(): String = correct(pending).also { pending = "" }
    private fun correct(raw: String): String {
        var text = raw
        for ((pattern, replacement) in compiled) text = pattern.replace(text) { replacement }
        return text.trim()
    }
}
