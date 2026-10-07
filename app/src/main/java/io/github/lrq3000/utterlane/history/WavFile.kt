package io.github.lrq3000.utterlane.history

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 WAV with bounded block IO. The capture writer and backlog reader use distinct handles. */
class WavFile(val file: File) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var bytes = ByteBuffer.allocate(6400).order(ByteOrder.LITTLE_ENDIAN)
    var samples: Long = 0
        private set

    init { output.setLength(44); writeHeader(output, 0) }

    fun append(data: ShortArray) {
        check(samples + data.size <= Int.MAX_VALUE / 2) { "WAV part is full" }
        if (bytes.capacity() < data.size * 2) bytes = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        bytes.asShortBuffer().put(data)
        output.seek(44 + samples * 2)
        output.write(bytes.array(), 0, data.size * 2)
        samples += data.size
    }

    fun read(offset: Long, count: Int): ShortArray = read(file, offset, minOf(count.toLong(), samples - offset).coerceAtLeast(0).toInt())

    override fun close() {
        try { writeHeader(output, samples * 2) } finally { output.close() }
    }

    /** One independent read handle per session/part; never shares the writer's seek position. */
    class Reader(file: File) : Closeable {
        private val input = RandomAccessFile(file, "r")
        private val bytes = ByteArray(6400)
        fun readInto(offset: Long, target: ShortArray, start: Int, count: Int) {
            require(count in 0..3200)
            input.seek(44 + offset * 2)
            input.readFully(bytes, 0, count * 2)
            ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(target, start, count)
        }
        override fun close() = input.close()
    }

    companion object {
        fun read(file: File, offset: Long, count: Int): ShortArray {
            require(offset >= 0 && count >= 0)
            RandomAccessFile(file, "r").use { input ->
                input.seek(44 + offset * 2)
                val bytes = ByteArray(count * 2)
                input.readFully(bytes)
                return ShortArray(count).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
            }
        }

        fun repair(file: File) {
            RandomAccessFile(file, "rw").use {
                require(it.length() >= 44) { "Incomplete WAV header" }
                it.setLength(44 + ((it.length() - 44) / 2) * 2)
                writeHeader(it, it.length() - 44)
            }
        }

        private fun writeHeader(output: RandomAccessFile, bytes: Long) {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVEfmt ".toByteArray())
            header.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(bytes.toInt())
            output.seek(0)
            output.write(header.array())
        }
    }
}
