package io.github.lrq3000.utterlane.home

/** Main-thread foreground ownership, including the stopSelf -> onDestroy interval. */
internal class HomeServiceConnection {
    private enum class Phase { DETACHED, REQUESTED, ATTACHED, STOPPING }
    private var token: String? = null
    private var phase = Phase.DETACHED
    val attached get() = phase == Phase.ATTACHED
    val pending get() = phase == Phase.REQUESTED
    val needsStart get() = !attached && !pending
    fun owns(token: String?) = token != null && token == this.token
    fun request(token: String) { this.token = token; phase = Phase.REQUESTED }
    fun started(token: String) { if (owns(token)) phase = Phase.ATTACHED }
    // stopSelf is asynchronous. A direct dialog Retry in this interval must
    // request replacement ownership now, not wait for an onDestroy relaunch.
    fun stopping(token: String) { if (owns(token)) phase = Phase.STOPPING }
    fun detached(token: String) { if (owns(token)) phase = Phase.DETACHED }
}
