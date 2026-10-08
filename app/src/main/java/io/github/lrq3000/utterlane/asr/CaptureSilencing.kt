package io.github.lrq3000.utterlane.asr

import java.util.concurrent.atomic.AtomicBoolean

/** Recorder-configuration bridge; native configuration reads belong to the worker. */
internal class CaptureSilencing(private val onChanged: (Boolean) -> Unit) {
    private var dirty = AtomicBoolean(false)
    private var query: () -> Boolean? = { null }
    private var value = false
    private var published: Boolean? = null
    fun opened(query: () -> Boolean?): () -> Unit {
        val pending = AtomicBoolean(true)
        dirty = pending
        this.query = query
        value = false
        published = null
        // Each callback closes over ONLY its recorder's flag. A callback already
        // executing when release occurs cannot write the replacement's state.
        return { pending.set(true) }
    }
    fun poll(): Boolean {
        if (dirty.getAndSet(false)) {
            query()?.let { value = it }
            if (published != value) { published = value; onChanged(value) }
        }
        return value
    }
}
