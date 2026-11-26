package io.github.lrq3000.utterlane.transcribe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A WAITING descriptor proves only that its input was staged. A fresh process, a refused launch, and a refused promotion all require the same explicit foreground resume. */
internal class QueueServiceAdmission {
    enum class Status { STOPPED, REQUESTED, RUNNING;
        fun needsResume(row: QueueRecord) = this == STOPPED && row.stage == QueueStage.WAITING
    }
    private val mutable = MutableStateFlow(Status.STOPPED)
    val status = mutable.asStateFlow()
    fun request(start: () -> Unit): Boolean {
        if (mutable.value != Status.STOPPED) return true
        mutable.value = Status.REQUESTED
        return try { start(); true } catch (_: RuntimeException) { refused(); false }
    }
    fun running() { mutable.value = Status.RUNNING }
    fun refused() { mutable.value = Status.STOPPED }
}
