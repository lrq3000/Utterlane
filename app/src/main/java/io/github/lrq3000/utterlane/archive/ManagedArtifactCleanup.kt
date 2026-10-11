package io.github.lrq3000.utterlane.archive

/** Synchronous worker/test port, one target at a time. The caller must know that
 * prior provider creation calls have settled before acknowledging absence. */
class ManagedArtifactCleanup(private val journal: ArchivalJobStore, private val provider: ExternalArtifacts) {
    fun run(target: ArchivalCleanup, safeToClean: (ArtifactOwner) -> Boolean): Boolean {
        if (journal.cleanup(target.key) == null) return true
        if (!journal.canClean(target.key)) return false
        if (!safeToClean(target.owner)) { journal.deferCleanupForLease(target.key); return false }
        var current = journal.cleanup(target.key) ?: return true
        current.name?.let { name ->
            val uri = provider.findExact(current.destination, name)
            if (uri == null) { journal.completeCleanup(current.key, absentConfirmed = true); return true }
            journal.resolvedCleanup(current.key, uri)
            current = checkNotNull(journal.cleanup(current.key))
        }
        if (!provider.delete(checkNotNull(current.uri))) return false
        journal.completeCleanup(current.key)
        return true
    }
}
