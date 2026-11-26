package io.github.lrq3000.utterlane.asr

/** The microphone worker and a destructive Settings confirmation share one capture-end fence. */
internal class CaptureSettingsExit {
    private var closing = false

    @Synchronized fun captureClosing() { closing = true }

    @Synchronized fun confirmDiscard(discard: () -> Unit): Boolean {
        if (closing) return false
        closing = true
        discard()
        return true
    }
}
