package com.translander.asr

/** A blocking PCM16 capture source. The worker owns resources; stop is thread-safe. */
interface AudioCapture {
    fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean = { true })
    fun stop()
}
