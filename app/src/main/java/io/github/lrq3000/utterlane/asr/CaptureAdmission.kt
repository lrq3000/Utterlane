package io.github.lrq3000.utterlane.asr

/** Hardware ownership ends when the recorder closes. The session remains resettable until its independent writer/recognizer cleanup finishes. */
internal class CaptureAdmission {
    class Lease(val reset: () -> Unit) {
        @Volatile var isLive = true
            private set
        fun stopped() { isLive = false }
    }
    private var hardware: Lease? = null
    private var failedRelease: Lease? = null
    private val sessions = mutableSetOf<Lease>()

    @Synchronized fun acquire(lease: Lease): Boolean {
        if (hardware != null) return false
        hardware = lease
        sessions.add(lease)
        return true
    }

    // The capture worker (or a later retry) is the only caller allowed to close hardware. No admission monitor is held across the platform call.
    fun releaseHardware(lease: Lease, close: () -> Unit): Unit = synchronized(lease) {
        if (synchronized(this) { hardware !== lease }) return
        lease.stopped()
        try { close() } catch (error: Exception) {
            synchronized(this) { failedRelease = lease }
            throw error
        }
        synchronized(this) { hardware = null; failedRelease = null }
    }

    @Synchronized fun finish(lease: Lease) {
        sessions.remove(lease)
    }

    @Synchronized fun needsReleaseRetry(lease: Lease): Boolean = failedRelease === lease
    @Synchronized fun releaseFailed(): Boolean = failedRelease != null
    @Synchronized fun resetTargets(): List<Lease> = (sessions + listOfNotNull(hardware)).toList()
    @Synchronized fun isBusy(): Boolean = hardware != null
    @Synchronized fun hasSessions(): Boolean = sessions.isNotEmpty() || hardware != null
}
