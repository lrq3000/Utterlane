package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptDocumentTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun boundedChunksReconstructEveryUnicodeCharacterAndWhitespace() {
        val text = ("Bonjour, déjà vu 🎙️. 日本語 العربية\nSecond paragraph with words.\n\n").repeat(200)
        val file = folder.newFile().apply { writeText(text) }
        for (size in listOf(7, 17, 2048)) {
            val document = TranscriptDocument(file, chunkBytes = size)
            val chunks = (0 until document.chunkCount).map(document::read)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= size + 68 })
        }
    }

    @Test fun capturedLengthDoesNotConsumeLaterAppends() {
        val file = folder.newFile().apply { writeText("One complete thought 🎙️.") }
        val before = TranscriptDocument(file, chunkBytes = 7)
        file.appendText(" And another thought.")
        assertEquals("One complete thought 🎙️.", (0 until before.chunkCount).joinToString("") { before.read(it) })
        val after = TranscriptDocument(file, chunkBytes = 7)
        assertEquals(file.readText(), (0 until after.chunkCount).joinToString("") { after.read(it) })
    }

    @Test fun emptyDocumentAndInvalidChunkHaveExplicitBoundaries() {
        val document = TranscriptDocument(folder.newFile())
        assertEquals(0, document.chunkCount)
        assertThrows(IllegalArgumentException::class.java) { document.read(0) }
    }

    @Test fun sourceAssociationSurvivesRecoveryAndExplicitDisposalRemovesIt() {
        val file = folder.newFile()
        val store = TranscriptStore(file)
        store.attachSource(TranscriptSource("audio-id", "saved-id", "Model name", "model-id"))
        store.append("A result to recover")
        assertEquals(file.length(), store.bytes)
        store.keepForRecovery()
        val recovered = TranscriptStore(file)
        assertEquals(TranscriptSource("audio-id", "saved-id", "Model name", "model-id"), recovered.source)
        val lease = recovered.acquire()
        recovered.dispose()
        assertTrue(file.exists())
        lease.close()
        assertFalse(file.exists())
        assertFalse(TranscriptSource.metadata(file).exists())
    }

    @Test fun oldTranscriptWithoutSourceMetadataRemainsReadable() {
        val file = folder.newFile().apply { writeText("Legacy result") }
        val store = TranscriptStore(file)
        assertEquals(TranscriptSource(), store.source)
        assertEquals("Legacy result", store.preview())
        store.dispose()
    }

    @Test fun provenanceExpiresWithTheLastTextWriteRatherThanItsOwnCreation() {
        val file = folder.newFile()
        val store = TranscriptStore(file)
        store.attachSource(TranscriptSource(audioId = "audio"))
        store.append("Recently extended transcript")
        store.keepForRecovery()
        val metadata = TranscriptSource.metadata(file)
        assertTrue(metadata.setLastModified(1000))
        assertTrue(file.setLastModified(5000))
        CacheArtifacts.prune(folder.root, 1000, now = 5500, referenceTime = TranscriptSource::retentionReference)
        assertTrue(file.exists()); assertTrue(metadata.exists())
        CacheArtifacts.prune(folder.root, 1000, now = 6000, referenceTime = TranscriptSource::retentionReference)
        assertFalse(file.exists()); assertFalse(metadata.exists())
    }

    @Test fun explicitlyDeletedWorkingTextCannotReopenWhileAnExportLeaseSurvives() {
        val file = folder.newFile()
        val store = TranscriptStore(file)
        store.attachSource(TranscriptSource(audioId = "audio"))
        store.append("Delete this result, including recovery")
        val export = store.acquire()
        try {
            store.dispose()
            assertTrue("The export still owns the bytes", file.exists())
            assertThrows(IllegalStateException::class.java) {
                TranscriptStore(file).keepForRecovery()
            }
            assertThrows(IllegalStateException::class.java) {
                store.attachSource(store.source.copy(modelName = "Late producer metadata"))
            }
            // A different directory has no in-memory cache registry entry,
            // reproducing the durable-disposition side of a process restart.
            val restarted = folder.newFolder()
            val copy = file.copyTo(java.io.File(restarted, file.name))
            TranscriptSource.metadata(file).copyTo(TranscriptSource.metadata(copy))
            assertThrows(IllegalStateException::class.java) { TranscriptStore(copy).keepForRecovery() }
            TranscriptStore.prune(restarted, Long.MAX_VALUE)
            assertFalse(copy.exists()); assertFalse(TranscriptSource.metadata(copy).exists())
        } finally { export.close() }
    }
}
