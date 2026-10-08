package io.github.lrq3000.utterlane.asr

import android.media.AudioRecord
import android.os.SystemClock
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.util.concurrent.atomic.AtomicBoolean

/** Nonblocking reads let Stop/Cancel and wake recovery work even when no frames arrive. */
internal class CaptureReadLoop(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val waitForFrames: () -> Unit = { Thread.sleep(10) },
    options: RuntimeOptions = RuntimeOptions()
) {
    private val snapshot = options.requireValid()
    private val buffers = CaptureBufferPolicy(snapshot)
    private val wakeRequested = AtomicBoolean(false)
    fun resumeAfterSleep() { wakeRequested.set(true) }

    fun run(read: (ShortArray) -> Int, reopen: () -> Unit, onSamples: (ShortArray) -> Unit,
        shouldContinue: () -> Boolean, canRecover: () -> Boolean = { true },
        routeRecoveryRequested: () -> Boolean = { false }) {
        val buffer = ShortArray(buffers.blockSamples)
        var recoveryDeadline: Long? = null
        var restarted = false
        fun recoverRoute(): Boolean {
            if (!routeRecoveryRequested()) return false
            // The route policy gives the old recorder a bounded drain interval.
            // After that, even continuously readable stale/silenced PCM must not
            // starve the one recovery attempt until its terminal deadline.
            if (shouldContinue()) {
                reopen()
                recoveryDeadline = null
                restarted = false
            }
            return true
        }
        while (shouldContinue()) {
            if (wakeRequested.getAndSet(false)) {
                // First drain any PCM already in AudioRecord. Reopening immediately
                // on screen-on would discard it even when the recorder is healthy.
                recoveryDeadline = clock() + snapshot.wakeRecoveryMs
                restarted = false
            }
            val count = read(buffer)
            when {
                count > 0 -> {
                    onSamples(buffer.copyOfRange(0, count))
                    // A single buffered pre-sleep block is not proof that capture
                    // resumed. Keep watching for new frames after draining it.
                    if (recoveryDeadline != null) recoveryDeadline = clock() + snapshot.wakeRecoveryMs
                    restarted = false
                    // Preserve the just-read block before any native recreation.
                    if (recoverRoute()) continue
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
                    recoveryDeadline = clock() + snapshot.wakeReopenMs
                }
                count < 0 -> error("AudioRecord read error: $count")
                else -> {
                    // Route recovery also works with the screen off. Callbacks
                    // never release native resources and Stop always wins.
                    if (recoverRoute()) continue
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
                        recoveryDeadline = clock() + snapshot.wakeReopenMs
                    }
                    waitForFrames()
                }
            }
        }
    }
}
