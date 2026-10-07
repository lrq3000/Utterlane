package io.github.lrq3000.utterlane.asr

import android.speech.SpeechRecognizer

data class SessionFailure(val kind: Kind, val message: String, val recoveryId: String? = null) {
    enum class Kind { BUSY, MODEL, AUDIO, INFERENCE, CAPACITY, NO_SPEECH }
    fun recognitionError(): Int = when (kind) {
        Kind.BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        Kind.AUDIO -> SpeechRecognizer.ERROR_AUDIO
        Kind.NO_SPEECH -> SpeechRecognizer.ERROR_NO_MATCH
        Kind.MODEL, Kind.INFERENCE, Kind.CAPACITY -> SpeechRecognizer.ERROR_SERVER
    }
}
