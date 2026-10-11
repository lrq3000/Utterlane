package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.history.*
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportedAudioCopyTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Test fun providerCloseFailureReleasesTheAlreadyPublishedLeaseForBothRetentionChoices() {
        for (choice in ImportRetentionChoice.entries) {
            val history = RecordingHistory(folder.newFolder())
            val closeError = IOException("Provider close failed")
            var closes = 0
            val source = object : ByteArrayInputStream(bytes) {
                override fun close() { closes++; throw closeError }
            }
            val error = assertThrows(IOException::class.java) {
                copyImportedAudio(history, source, "wav", AudioImportMetadata(), choice)
            }
            assertSame(closeError, error)
            assertEquals(1, closes)
            val copy = history.list().single()
            assertArrayEquals(bytes, copy.part(0).readBytes())
            assertTrue("Preserve completed input for recovery when import reports failure", copy.needsRecovery)
            if (choice == ImportRetentionChoice.KEEP) history.prune(HistoryRetention.NONE) else history.delete(copy.id)
            assertFalse("No stranded lease may block pruning/deletion after the caller received an exception: " +
                "choice=$choice leases=${history.hasLeases(copy.id)} remaining=${copy.directory.list()?.toList()}", copy.directory.exists())
        }
    }

    @Test fun successfulCopyClosesProviderButTransfersProtectedAudioOwnershipToCaller() {
        val history = RecordingHistory(folder.root)
        var closed = false
        val source = object : ByteArrayInputStream(bytes) { override fun close() { closed = true } }
        val owned = copyImportedAudio(history, source, "wav", AudioImportMetadata(), ImportRetentionChoice.KEEP)
        assertTrue(closed)
        assertArrayEquals(bytes, owned.entry.part(0).readBytes())
        history.prune(HistoryRetention.NONE)
        assertTrue(owned.entry.part(0).exists())
        owned.lease.close()
        history.prune(HistoryRetention.NONE)
        assertTrue(history.list().isEmpty())
    }

    @Test fun copyFailureKeepsItsPrimaryErrorAndSuppressesProviderCloseErrorWithoutPublishing() {
        val history = RecordingHistory(folder.root)
        val readError = IOException("Provider read failed")
        val closeError = IOException("Provider close failed")
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw readError
            override fun close(): Unit = throw closeError
        }
        val error = assertThrows(IOException::class.java) {
            copyImportedAudio(history, source, "wav", AudioImportMetadata(), ImportRetentionChoice.KEEP)
        }
        assertSame(readError, error)
        assertEquals(listOf(closeError), error.suppressed.toList())
        assertTrue(history.list().isEmpty())
        assertTrue(folder.root.list()!!.isEmpty())
    }
}
