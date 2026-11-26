package io.github.lrq3000.utterlane.transcribe

/** A ready row and its notification cannot be committed atomically. Publish first, then mark the receipt; an interrupted post is safely repeatable under the same Android notification ID. */
internal class QueueTerminalDelivery(private val journal: QueueJournal) {
    private var anchor: QueueRecord? = null
    fun deliver(id: String, post: (QueueRecord) -> Boolean): Boolean = synchronized(journal) {
        val row = journal.find(id) ?: return false
        if (!row.stage.reviewable || row.terminalPosted || anchor?.id == id) return false
        if (!post(row)) return false
        // Acknowledge uses the same monitor. It cannot remove a result between this check and notify and have a stale notification reappear afterward.
        try {
            journal.update(row.copy(terminalPosted = true))
            true
        } catch (e: java.io.IOException) {
            unrecorded(e)
        } catch (e: android.system.ErrnoException) {
            unrecorded(e) // Android's directory fsync reports errno directly.
        }
    }
    private fun unrecorded(error: Exception): Boolean {
        java.util.logging.Logger.getLogger("TranscriptionQueue").log(java.util.logging.Level.WARNING,
            "Result notification posted but receipt not saved; delivery remains pending", error)
        return false
    }
    fun acknowledge(id: String, remove: (QueueRecord) -> Unit): Boolean = synchronized(journal) {
        val row = journal.find(id)?.takeIf { it.stage.reviewable } ?: return false
        // Cancel before unlinking the only durable record of this notification's Android identity. A deferred foreground detach also consults the journal.
        remove(row)
        journal.acknowledge(id)
    }

    fun attach(candidate: QueueRecord?, promote: (QueueRecord?) -> Unit) = synchronized(journal) {
        val current = candidate?.let { journal.find(it.id) }?.takeIf { it.stage.needsProcessingService }
        promote(current)
        anchor = current
    }
    fun detach(stop: (Boolean) -> Unit, remove: (QueueRecord) -> Unit, post: (QueueRecord) -> Boolean) = synchronized(journal) {
        val old = anchor
        stop(old != null)
        anchor = null
        if (old != null) {
            val current = journal.find(old.id)
            if (current == null) remove(old)
            else if (current.stage.reviewable) deliver(current.id, post)
            else post(current)
        }
    }
}
