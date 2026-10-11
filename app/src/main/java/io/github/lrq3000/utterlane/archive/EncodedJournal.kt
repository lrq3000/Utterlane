package io.github.lrq3000.utterlane.archive

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** One continuous codec stream. No per-chunk encoder restarts, hence no repeated
 * priming gaps. A length-delimited, checksummed packet is at most 64 KiB. The
 * authoritative byte limit lives in the separately fsynced source checkpoint. */
object EncodedJournal {
    const val MAX_PACKET = 65536
    data class Packet(val bytes: ByteArray, val ptsUs: Long, val frames: Int)
    class Writer(file: File) : Closeable {
        private val output: RandomAccessFile
        init {
            check(!file.exists() || file.length() == 0L) { "Encoded prefix is authoritative; never overwrite it on retry" }
            output = RandomAccessFile(file, "rw")
        }
        fun append(bytes: ByteArray, ptsUs: Long, frames: Int) {
            require(bytes.size in 1..MAX_PACKET && frames > 0)
            output.writeInt(bytes.size); output.writeLong(ptsUs); output.writeInt(frames)
            output.writeInt(CRC32().apply { update(bytes) }.value.toInt()); output.write(bytes)
        }
        fun checkpoint(): Long { output.fd.sync(); return output.filePointer }
        override fun close() = output.close()
    }
    fun read(file: File, committedBytes: Long, packet: (Packet) -> Unit) {
        RandomAccessFile(file, "r").use { input ->
            check(committedBytes in 0..input.length()) { "Encoded checkpoint is truncated" }
            while (input.filePointer < committedBytes) {
                check(committedBytes - input.filePointer >= 20) { "Incomplete encoded packet" }
                val size = input.readInt(); val pts = input.readLong(); val frames = input.readInt(); val crc = input.readInt()
                check(size in 1..MAX_PACKET && frames > 0 && input.filePointer + size <= committedBytes)
                val bytes = ByteArray(size); input.readFully(bytes)
                check(CRC32().apply { update(bytes) }.value.toInt() == crc) { "Encoded packet checksum mismatch" }
                packet(Packet(bytes, pts, frames))
            }
        }
    }
}
