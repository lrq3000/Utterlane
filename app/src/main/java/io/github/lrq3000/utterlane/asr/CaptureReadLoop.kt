package io.github.lrq3000.utterlane.asr

import android.media.AudioRecord
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Nonblocking reads let Stop/Cancel and wake recovery work even when no frames arrive. */
internal class CaptureReadLoop(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val waitForFrames: () -> Unit = { Thread.sleep(10) }
) {
    private val wakeRequested = AtomicBoolean(false)
    fun resumeAfterSleep() { wakeRequested.set(true) }

    fun run(read: (ShortArray) -> Int, reopen: () -> Unit, onSamples: (ShortArray) -> Unit,
        shouldContinue: () -> Boolean, canRecover: () -> Boolean = { true }) {
        val buffer = ShortArray(AudioRecorder.SAMPLE_RATE / 20)
        var recoveryDeadline: Long? = null
        var restarted = false
        while (shouldContinue()) {
            if (wakeRequested.getAndSet(false)) {
                // First drain any PCM already in AudioRecord. Reopening immediately
                // on screen-on would discard it even when the recorder is healthy.
                recoveryDeadline = clock() + 1500
                restarted = false
            }
            val count = read(buffer)
            when {
                count > 0 -> {
                    onSamples(buffer.copyOfRange(0, count))
                    // A single buffered pre-sleep block is not proof that capture
                    // resumed. Keep watching for new frames after draining it.
                    if (recoveryDeadline != null) recoveryDeadline = clock() + 1500
                    restarted = false
                }
                count == AudioRecord.ERROR_DEAD_OBJECT && !canRecover() -> {
                    recoveryDeadline = null
                    restarted = false
                    waitForFrames()
                }
                count == AudioRecord.ERROR_DEAD_OBJECT && !restarted -> {
                    if (!shouldContinue()) break
                    reopen()
                    restarted = true
                    recoveryDeadline = clock() + 5000
                }
                count < 0 -> error("AudioRecord read error: $count")
                else -> {
                    val deadline = recoveryDeadline
                    if (deadline != null && clock() >= deadline) {
                        if (!canRecover()) {
                            // A later sleep is not a failed wake. The next wake
                            // callback rearms recovery after Android permits it.
                            recoveryDeadline = null
                            restarted = false
                            waitForFrames()
                            continue
                        }
                        // A dead capture must close its queue and let accepted audio
                        // finish, rather than leave the session in an endless drain.
                        check(!restarted) { "Microphone did not resume after waking" }
                        if (!shouldContinue()) break
                        reopen()
                        restarted = true
                        recoveryDeadline = clock() + 5000
                    }
                    waitForFrames()
                }
            }
        }
    }
}
