package io.github.lrq3000.utterlane.archive

import java.io.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivalTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private val journal by lazy { ArchivalJobStore(File(temp.root, "jobs")) }
    private val output by lazy { temp.newFile("external.md") }
    private val uri = "content://fake/document/opaque%3A42"
    private var created = false
    private var lookups = 0
    private val adapter = object : ExternalArtifacts {
        override fun findExact(folder: String, name: String): String? { lookups++; return if (created) uri else null }
        override fun create(folder: String, name: String, mime: String): String { created = true; return uri }
        override fun write(uri: String, body: (OutputStream) -> Unit) { output.outputStream().use(body) }
        override fun delete(uri: String) = !output.exists() || output.delete()
    }
    private fun job(op: ArchivalOperation = ArchivalOperation.SAVE_MARKDOWN): ArchivalJob {
        journal.enqueue(ArchivalRequest(ArtifactOwner(ArtifactKind.TRANSCRIPT, "text"), op,
            File(temp.root, "source"), "content://fake/tree/folder", "transcript.md", "text/markdown", created = 123, model = "Actual v3"))
        return checkNotNull(journal.claim())
    }

    @Test fun `filename uses source chronology actual model and filesystem safe characters`() {
        assertEquals("2026-10-10_09-30-15_Parakeet-v3.md", ManagedMarkdown.filename(
            Instant.parse("2026-10-10T09:30:15Z").toEpochMilli(), "Parakeet v3", ZoneOffset.UTC))
        val name = ManagedMarkdown.filename(0, "../bad\\name:\n*?<>|".repeat(100), ZoneOffset.UTC)
        assertTrue(name.length <= 180)
        assertFalse(name.any { it in "/\\:\n*?<>|" })
    }

    @Test fun `complete disk text streams beyond preview and preserves paragraphs and speakers`() {
        val source = temp.newFile("long.txt")
        source.bufferedWriter().use { out -> repeat(50000) { out.write("Speaker 1: héllo 世界\n\nSpeaker 2: yes.\n") } }
        val j = job()
        var maxRead = 0
        source.inputStream().use { raw ->
            val bounded = object : FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { maxRead = maxOf(maxRead, len); return super.read(b, off, len) }
                override fun readAllBytes(): ByteArray = error("Unbounded read")
            }
            ArchivalTransfer.execute(j, bounded, source.length(), adapter, { journal.target(j.id, j.generation, it) })
        }
        assertTrue(maxRead in 1..65536)
        output.bufferedReader().use { result ->
            assertEquals("# Transcript", result.readLine())
            assertEquals("", result.readLine())
            assertTrue(result.readLine().startsWith("Date: "))
            assertEquals("Model: Actual v3", result.readLine())
            assertEquals("", result.readLine())
            source.bufferedReader().use { original ->
                while (true) { val expected = original.readLine() ?: break; assertEquals(expected, result.readLine()) }
            }
            assertNull(result.readLine())
        }
    }

    @Test fun `delete at target callback never writes and exact target remains recoverable`() {
        val j = job()
        assertThrows(InterruptedIOException::class.java) {
            ArchivalTransfer.execute(j, "words".byteInputStream(), 5, adapter, {
                journal.deleteOwner(j.request.owner)
                journal.target(j.id, j.generation, it)
            })
        }
        assertEquals(0L, output.length())
        assertEquals(setOf(uri), targets(journal, j.id).map { it.uri }.toSet())
    }

    @Test fun `copy is byte exact and truncated source is not a success`() {
        val j = job(ArchivalOperation.COPY_AUDIO)
        val bytes = ByteArray(150000) { (it % 256).toByte() }
        ArchivalTransfer.execute(j, bytes.inputStream(), bytes.size.toLong(), adapter, { journal.target(j.id, j.generation, it) })
        assertArrayEquals(bytes, output.readBytes())
        assertThrows(EOFException::class.java) {
            ArchivalTransfer.execute(j, bytes.inputStream(), bytes.size + 1L, adapter, { true })
        }
    }

    @Test fun `fresh transfer creates without routine folder enumeration`() {
        val j = job()
        ArchivalTransfer.execute(j, "words".byteInputStream(), 5, adapter, { true })
        assertEquals(0, lookups)
    }

    @Test fun `reconciliation streams unrelated children but accepts only one exact journal identity`() {
        val name = job().targetName
        var visited = 0
        val children = sequence {
            repeat(100000) { visited++; yield("ordinary-$it.md" to "content://p/$it") }
            yield(name to uri)
        }
        assertEquals(uri, PendingArtifactName.resolve(name, children))
        assertEquals(100000, visited)
        assertThrows(IllegalStateException::class.java) {
            PendingArtifactName.resolve(name, sequenceOf(name to uri, name to "content://p/ambiguous"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingArtifactName.resolve("ordinary.md", sequenceOf("ordinary.md" to uri))
        }
    }

    @Test fun `unicode model filename leaves byte room for immutable job identity`() {
        val request = job().request.copy(displayName = ManagedMarkdown.filename(0, "漢字".repeat(100), ZoneOffset.UTC))
        val named = ArchivalJob(java.util.UUID.randomUUID().toString(), request, generation = Long.MAX_VALUE)
        assertTrue(named.targetName.toByteArray(Charsets.UTF_8).size <= 255)
    }
}
