package io.github.lrq3000.utterlane.asr

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Active owners/readers protect cache artifacts; filesystem deletion always runs on IO. */
object CacheArtifacts {
    private data class State(var count: Int = 0, var pendingDelete: Boolean = false, var deleting: Boolean = false)
    private val states = mutableMapOf<String, State>()

    fun acquire(file: File): Closeable {
        synchronized(states) {
            val state = states.getOrPut(file.absolutePath) { State() }
            check(!state.deleting) { "Artifact expired" }
            state.count++
        }
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                val delete = synchronized(states) {
                    val state = checkNotNull(states[file.absolutePath])
                    state.count--
                    if (state.count == 0 && state.pendingDelete) { state.deleting = true; true }
                    else { if (state.count == 0) states.remove(file.absolutePath); false }
                }
                if (delete) delete(file)
            }
        }
    }

    fun deleteWhenReleased(file: File) {
        val delete = synchronized(states) {
            val state = states.getOrPut(file.absolutePath) { State() }
            state.pendingDelete = true
            if (state.count == 0 && !state.deleting) { state.deleting = true; true } else false
        }
        if (delete) delete(file)
    }

    fun prune(directory: File, maximumAgeMs: Long, now: Long = System.currentTimeMillis(), includeDirectories: Boolean = true,
        referenceTime: (File) -> Long = { it.lastModified() }) {
        directory.listFiles()?.forEach { file ->
            if (!includeDirectories && file.isDirectory) return@forEach
            val reference = referenceTime(file)
            val delete = synchronized(states) {
                val state = states[file.absolutePath]
                val expired = now >= reference && now - reference >= maximumAgeMs
                if ((expired || state?.pendingDelete == true) && (state?.count ?: 0) == 0 && state?.deleting != true) {
                    states.getOrPut(file.absolutePath) { State() }.deleting = true
                    true
                } else false
            }
            if (delete) delete(file)
        }
    }

    private fun delete(file: File) {
        // Do not hold the registry lock while removing a multi-part export folder.
        // Independent capture sessions can acquire their new files immediately.
        try { file.deleteRecursively() } finally {
            synchronized(states) {
                if (!file.exists()) states.remove(file.absolutePath)
                else states[file.absolutePath]?.apply { deleting = false; pendingDelete = true }
            }
        }
    }
}
