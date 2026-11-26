package io.github.lrq3000.utterlane.asr

/** Hardware ownership ends when the recorder closes. The session remains resettable until its independent writer/recognizer cleanup finishes. */
internal class CaptureAdmission {
    class Lease(val reset: () -> Unit) {
        @Volatile var isLive = true
            private set
        fun stopped() { isLive = false }
    }
    private var hardware: Lease? = null
    private val sessions = mutableSetOf<Lease>()

    @Synchronized fun acquire(lease: Lease): Boolean {
        if (hardware != null) return false
        hardware = lease
        sessions.add(lease)
        return true
    }

    @Synchronized fun hardwareClosed(lease: Lease) {
        lease.stopped()
        if (hardware === lease) hardware = null
    }

    @Synchronized fun finish(lease: Lease) {
        hardwareClosed(lease)
        sessions.remove(lease)
    }

    @Synchronized fun resetTargets(): List<Lease> = sessions.toList()
    @Synchronized fun isBusy(): Boolean = hardware != null
    @Synchronized fun hasSessions(): Boolean = sessions.isNotEmpty()
}
