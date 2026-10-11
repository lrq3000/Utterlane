package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions

/** A blocking PCM16 capture source. The worker owns resources; stop is thread-safe. */
interface AudioCapture {
    fun configureMicrophone(options: io.github.lrq3000.utterlane.audio.MicrophoneOptions) {}
    fun configureFormat(requested: io.github.lrq3000.utterlane.archive.PcmFormat): io.github.lrq3000.utterlane.archive.PcmFormat =
        io.github.lrq3000.utterlane.archive.PcmFormat()
    fun configureFormat(requested: io.github.lrq3000.utterlane.archive.PcmFormat, options: RuntimeOptions): io.github.lrq3000.utterlane.archive.PcmFormat =
        configureFormat(requested)
    fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean = { true })
    // Existing injected PCM sources need not know about Android buffer sizing.
    // Forward to their original entry point rather than replacing the fake source.
    fun startRecording(options: RuntimeOptions, onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean = { true }) =
        startRecording(onSamples, shouldContinue)
    fun stop()
    fun setObserver(observer: CaptureObserver) {}
    fun resumeAfterSleep() {}
    /** Called after the blocking capture worker returned, including failed setup. */
    fun releaseAfterCapture() {}
}

interface CaptureObserver {
    fun onStarted() {}
    fun onSilenced(silenced: Boolean) {}
    fun onInputChanged(state: io.github.lrq3000.utterlane.audio.CaptureInputState) {}
}
