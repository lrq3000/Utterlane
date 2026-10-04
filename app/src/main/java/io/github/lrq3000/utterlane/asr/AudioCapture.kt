package io.github.lrq3000.utterlane.asr

/** A blocking PCM16 capture source. The worker owns resources; stop is thread-safe. */
interface AudioCapture {
    fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean = { true })
    fun stop()
    fun setObserver(observer: CaptureObserver) {}
    fun resumeAfterSleep() {}
}

interface CaptureObserver {
    fun onStarted() {}
    fun onSilenced(silenced: Boolean) {}
}
