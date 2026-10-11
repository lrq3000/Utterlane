package io.github.lrq3000.utterlane.archive

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** Codec-free, constant-memory conventional ISO BMFF remux. MediaMuxer keeps a
 * sample index in RAM; this writer streams its stsz table from the packet journal
 * instead. One contiguous mdat chunk needs only one co64 entry. Standard edit
 * lists trim priming/padding; mdhd/stts describe full physical codec frames so
 * Android can derive encoder-padding rather than hiding that tail. No frame list is
 * resident, including during multi-day recording finalization or prefix repair. */
object DiskIndexedAudioMp4 {
    const val MAX_PACKETS = Int.MAX_VALUE / 8
    fun write(journal: File, committedBytes: Long, output: File, quality: AudioQuality,
        config: ByteArray, delayFrames: Int, sourceFrames: Long) {
        require(sourceFrames > 0 && delayFrames >= 0 && config.size <= 64)
        val rate = quality.sampleRate
        val mediaFrames = sourceFrames + delayFrames
        var packets = 0
        var packetFrames = 0
        var encodedFrames = 0L
        var payloadBytes = 0L
        EncodedJournal.read(journal, committedBytes) { packet ->
            if (encodedFrames < mediaFrames) {
                check(packets < MAX_PACKETS) { "Audio exceeds the MP4 sample table limit" }
                if (packets == 0) packetFrames = packet.frames else check(packet.frames == packetFrames)
                packets++; payloadBytes += packet.bytes.size
            }
            encodedFrames += packet.frames
        }
        check(encodedFrames >= mediaFrames && packets > 0) { "Encoded checkpoint does not cover the requested source interval" }
        val physicalFrames = packets.toLong() * packetFrames
        fun matrix(out: DataOutputStream) { intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach(out::writeInt) }
        fun descriptor(tag: Int, bytes: ByteArray) = data { writeByte(tag); require(bytes.size < 128); writeByte(bytes.size); write(bytes) }
        val sampleEntry = box(if (quality.format == SavedAudioFormat.THREE_GP) {
            if (rate == 8000) "samr" else "sawb"
        } else "mp4a") {
            write(ByteArray(6)); writeShort(1); writeLong(0); writeShort(quality.channels); writeShort(16)
            writeInt(0); writeInt(rate shl 16)
            if (quality.format == SavedAudioFormat.THREE_GP) write(box("damr") {
                writeInt(0); writeByte(0); writeShort(if (rate == 8000) 0x81ff else 0x1ff); writeByte(0); writeByte(1)
            }) else write(box("esds") {
                writeInt(0)
                write(descriptor(3, data {
                    writeShort(1); writeByte(0)
                    write(descriptor(4, data {
                        writeByte(0x40); writeByte(0x15); writeByte(0); writeShort(0)
                        writeInt(quality.effectiveBitRate); writeInt(quality.effectiveBitRate)
                        write(descriptor(5, config))
                    }))
                    write(descriptor(6, byteArrayOf(2)))
                }))
            })
        }
        val mvhd = box("mvhd") {
                writeInt(0x01000000); writeLong(0); writeLong(0); writeInt(rate); writeLong(sourceFrames)
                writeInt(0x10000); writeShort(0x100); write(ByteArray(10)); matrix(this); write(ByteArray(24)); writeInt(2)
        }
        val tkhd = box("tkhd") {
                    writeInt(0x01000007); writeLong(0); writeLong(0); writeInt(1); writeInt(0); writeLong(sourceFrames)
                    writeLong(0); writeShort(0); writeShort(0); writeShort(0x100); writeShort(0); matrix(this); writeLong(0)
        }
        val edts = box("edts") { write(box("elst") {
                    writeInt(0x01000000); writeInt(1); writeLong(sourceFrames); writeLong(delayFrames.toLong()); writeShort(1); writeShort(0)
        }) }
        val mdhd = box("mdhd") {
                        writeInt(0x01000000); writeLong(0); writeLong(0); writeInt(rate); writeLong(physicalFrames); writeShort(0x55c4); writeShort(0)
        }
        val hdlr = box("hdlr") { writeLong(0); writeBytes("soun"); write(ByteArray(12)); writeBytes("Utterlane audio\u0000") }
        val smhd = box("smhd") { writeLong(0) }
        val dinf = box("dinf") { write(box("dref") { writeInt(0); writeInt(1); write(box("url ") { writeInt(1) }) }) }
        val stsd = box("stsd") { writeInt(0); writeInt(1); write(sampleEntry) }
        val stts = box("stts") {
            writeInt(0); writeInt(1); writeInt(packets); writeInt(packetFrames)
        }
        val stsc = box("stsc") { writeInt(0); writeInt(1); writeInt(1); writeInt(packets); writeInt(1) }
        val ftyp = box("ftyp") {
            writeBytes(if (quality.format == SavedAudioFormat.THREE_GP) "3gp6" else "M4A ")
            writeInt(0); writeBytes("isommp41")
        }
        val co64 = box("co64") { writeInt(0); writeInt(1); writeLong(ftyp.size + 16L) }
        val stszSize = 20L + packets * 4L
        val stblSize = 8 + stsd.size + stts.size + stsc.size + stszSize + co64.size
        val minfSize = 8 + smhd.size + dinf.size + stblSize
        val mdiaSize = 8 + mdhd.size + hdlr.size + minfSize
        val trakSize = 8 + tkhd.size + edts.size + mdiaSize
        val moovSize = 8 + mvhd.size + trakSize
        check(moovSize <= Int.MAX_VALUE)
        output.outputStream().use { stream ->
            val out = DataOutputStream(java.io.BufferedOutputStream(stream, 65536))
            fun header(type: String, size: Long) { out.writeInt(size.toInt()); out.writeBytes(type) }
            out.write(ftyp)
            out.writeInt(1); out.writeBytes("mdat"); out.writeLong(payloadBytes + 16)
            var count = 0
            EncodedJournal.read(journal, committedBytes) { packet ->
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                if (count++ < packets) out.write(packet.bytes)
            }
            header("moov", moovSize); out.write(mvhd)
            header("trak", trakSize); out.write(tkhd); out.write(edts)
            header("mdia", mdiaSize); out.write(mdhd); out.write(hdlr)
            header("minf", minfSize); out.write(smhd); out.write(dinf)
            header("stbl", stblSize); out.write(stsd); out.write(stts); out.write(stsc)
            header("stsz", stszSize); out.writeInt(0); out.writeInt(0); out.writeInt(packets)
            count = 0
            EncodedJournal.read(journal, committedBytes) { packet -> if (count++ < packets) out.writeInt(packet.bytes.size) }
            out.write(co64); out.flush()
            stream.fd.sync()
        }
    }
    private fun data(body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(body)
    }.toByteArray()
    private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
        val payload = data(body)
        return data { writeInt(payload.size + 8); writeBytes(type); write(payload) }
    }
}
