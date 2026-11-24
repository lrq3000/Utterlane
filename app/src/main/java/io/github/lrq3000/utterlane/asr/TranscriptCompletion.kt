package io.github.lrq3000.utterlane.asr

/** The state of one text version, not the state of its audio or later retries. */
enum class TranscriptCompletion {
    UNKNOWN, INCOMPLETE, COMPLETE;

    companion object {
        fun read(value: String?): TranscriptCompletion = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}
