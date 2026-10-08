package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions

/** A blocking PCM16 capture source. The worker owns resources; stop is thread-safe. */
interface AudioCapture {
    fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean = { true })
    // Existing injected PCM sources need not know about Android buffer sizing.
    // Forward to their original entry point rather than replacing the fake source.
    fun startRecording(options: RuntimeOptions, onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean = { true }) =
        startRecording(onSamples, shouldContinue)
    fun stop()
    fun setObserver(observer: CaptureObserver) {}
    fun resumeAfterSleep() {}
}

interface CaptureObserver {
    fun onStarted() {}
    fun onSilenced(silenced: Boolean) {}
    fun onInputChanged(state: io.github.lrq3000.utterlane.audio.CaptureInputState) {}
}
