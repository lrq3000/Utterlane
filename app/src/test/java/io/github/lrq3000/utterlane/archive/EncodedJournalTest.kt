package io.github.lrq3000.utterlane.archive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class EncodedJournalTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun tornUncommittedTailDoesNotChangeAuthoritativePrefix() {
        val file = temp.newFile()
        val end = EncodedJournal.Writer(file).use { writer ->
            writer.append(byteArrayOf(1, 2, 3), -64000, 1024)
            writer.append(byteArrayOf(4, 5), 0, 1024)
            writer.checkpoint()
        }
        RandomAccessFile(file, "rw").use { it.seek(it.length()); it.writeInt(100); it.write(byteArrayOf(99)) }
        val packets = mutableListOf<ByteArray>()
        EncodedJournal.read(file, end) { packets += it.bytes }
        assertEquals(2, packets.size)
        assertArrayEquals(byteArrayOf(4, 5), packets[1])
        assertThrows(IllegalStateException::class.java) { EncodedJournal.Writer(file) }
    }
    @Test fun corruptCommittedPacketIsNotSilentlySkipped() {
        val file = temp.newFile()
        val end = EncodedJournal.Writer(file).use { it.append(byteArrayOf(7, 8, 9), 0, 1024); it.checkpoint() }
        RandomAccessFile(file, "rw").use { it.seek(it.length() - 1); it.write(10) }
        assertThrows(IllegalStateException::class.java) { EncodedJournal.read(file, end) {} }
    }
    @Test fun remuxUsesEditListAndDiskSampleTableWithoutAnInMemorySampleIndex() {
        val file = temp.newFile()
        val end = EncodedJournal.Writer(file).use { writer ->
            repeat(4) { writer.append(byteArrayOf(1, 2, 3), it * 64000L - 64000, 1024) }
            writer.checkpoint()
        }
        val output = temp.newFile()
        DiskIndexedAudioMp4.write(file, end, output, AudioQuality(compress = true), byteArrayOf(0x14, 0x08), 1024, 2050)
        val text = output.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("elst"))
        assertFalse(text.contains("moof"))
        assertTrue(text.contains("mp4a"))
        assertTrue(text.contains("stsz"))
        assertTrue(text.contains("co64"))
        assertEquals("mdhd must expose physical padding so Android derives encoder-padding from elst",
            4096L, java.nio.ByteBuffer.wrap(output.readBytes()).getLong(text.indexOf("mdhd") + 28))
    }
}
