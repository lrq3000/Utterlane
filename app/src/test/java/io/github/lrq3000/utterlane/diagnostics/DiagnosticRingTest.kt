package io.github.lrq3000.utterlane.diagnostics

import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticRingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rotationCountsUtf8BytesAndNeverSplitsRecords() {
        val root = File(temporary.root, "logs")
        val files = DiagnosticRingFiles(root, maxFileBytes = 40)
        val line = "{\"value\":\"ééé\"}\n".toByteArray(Charsets.UTF_8)
        repeat(30) { files.append(line) }
        val logs = root.listFiles()!!.filter { it.extension == "jsonl" }
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.length() <= 40 })
        assertTrue(logs.sumOf { it.length() } <= 80)
        logs.forEach { file -> assertTrue(file.readLines().all { it == line.toString(Charsets.UTF_8).trimEnd() }) }
        val before = logs.sumOf { it.length() }
        assertFalse(files.append(ByteArray(41)))
        assertEquals(before, logs.sumOf { it.length() })
    }

    @Test fun snapshotIsImmutableAndClearRemovesRingAndExports(): Unit = runBlocking {
        val root = File(temporary.root, "logs")
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(root), { DiagnosticEnvironment() })
        log.offer(DiagnosticRecord.Capture(enabledOptions, "capturing", 16000, 0))
        val snapshot = log.snapshot()
        val initial = contents(snapshot)
        assertTrue(initial.contains("\"captured_samples\":16000"))
        log.offer(DiagnosticRecord.Capture(enabledOptions, "capturing", 32000, 16000))
        assertTrue(contents(log.snapshot()).contains("\"captured_samples\":32000"))
        assertEquals(initial, contents(snapshot))
        log.clear()
        assertFalse(snapshot.exists())
        assertEquals("", contents(log.snapshot()))
        log.close()
    }

    @Test fun oldFilesExpireAndRepeatedExportsRemainBounded() {
        var now = 1000000000L
        val root = File(temporary.root, "logs")
        val files = DiagnosticRingFiles(root, maxFileBytes = 64, clock = { now })
        files.append("{}\n".toByteArray())
        repeat(8) { files.snapshot() }
        assertEquals(2, File(root, "exports").listFiles()!!.size)
        root.listFiles()!!.filter { it.isFile }.forEach { assertTrue(it.setLastModified(now)) }
        File(root, "exports").listFiles()!!.forEach { assertTrue(it.setLastModified(now)) }
        now += 8 * 86400000L
        files.prune()
        assertTrue(root.walkTopDown().filter { it.isFile }.none())
    }

    @Test fun incompleteRecordFromInterruptedProcessIsNotExported() {
        val root = temporary.newFolder("interrupted")
        File(root, "current.jsonl").writeText("{\"complete\":true}\n{\"partial\"")
        val files = DiagnosticRingFiles(root)
        files.append("{\"next\":true}\n".toByteArray())
        assertEquals("{\"complete\":true}\n{\"next\":true}\n", contents(files.snapshot()))
    }

    private fun contents(file: File): String = ZipFile(file).use { zip ->
        zip.entries().asSequence().joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
    }
}
