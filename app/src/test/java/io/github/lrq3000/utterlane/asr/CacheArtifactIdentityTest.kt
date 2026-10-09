package io.github.lrq3000.utterlane.asr

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Android's /data/user/0 and /data/data aliases must share one lease identity.
 * Dot-segment aliases exercise the same boundary without host symlink privileges. */
class CacheArtifactIdentityTest {
    @get:Rule val folder = TemporaryFolder()

    private fun alias(file: File): File {
        val child = File(file.parentFile, "alias").apply { mkdirs() }
        return File(child, "../${file.name}").also { assertEquals(file.canonicalPath, it.canonicalPath) }
    }

    @Test fun aliasedWorkingDeletionPreservesLeasedTextAndMarkerUntilFinalRelease() {
        val file = folder.newFile("working.txt").canonicalFile
        val store = TranscriptStore(file)
        store.append("Leased full transcript")
        store.attachSource(TranscriptSource(transcriptId = "saved-id"))
        val reader = store.acquire()
        try {
            TranscriptStore.deleteArtifacts(alias(file))
            store.keepForRecovery()
            assertTrue("Alias deletion must respect the actual reader lease", file.isFile)
            assertEquals("Leased full transcript", file.readText())
            assertTrue(TranscriptSource.read(file).discarded)
        } finally { store.keepForRecovery(); reader.close() }
        assertFalse(file.exists())
        assertFalse(TranscriptSource.metadata(file).exists())
    }

    @Test fun canonicalDeletionWaitsForReadersAcquiredThroughEitherAlias() {
        val file = folder.newFile("shared.txt").canonicalFile
        val first = CacheArtifacts.acquire(file)
        val second = CacheArtifacts.acquire(alias(file))
        try {
            CacheArtifacts.deleteWhenReleased(file)
            first.close()
            assertTrue("The alias reader still owns these bytes", file.isFile)
        } finally { first.close(); second.close() }
        assertFalse(file.exists())
    }

    @Test fun pruningThroughAliasDoesNotExpireALeasedArtifact() {
        val file = folder.newFile("retained.txt").canonicalFile
        val alias = alias(file)
        CacheArtifacts.acquire(file).use {
            CacheArtifacts.prune(alias.parentFile, maximumAgeMs = 0, includeDirectories = false)
            assertTrue("Prune must use the same lease identity as acquire", file.isFile)
        }
        CacheArtifacts.prune(alias.parentFile, maximumAgeMs = 0, includeDirectories = false)
        assertFalse(file.exists())
    }
}
