package io.github.lrq3000.utterlane.home

import kotlinx.coroutines.yield

/** Confirm terminal idleness rather than stopping in the middle of a model handoff. */
internal object HomeServiceIdleStop {
    suspend fun recheck(isIdle: () -> Boolean, onIdle: () -> Unit) {
        // The shared model clears importing immediately before starting its
        // automatic transcription. Let that Main turn finish, then re-read both
        // work and token ownership; no timer or speculative progress is involved.
        yield()
        if (isIdle()) onIdle()
    }
}
