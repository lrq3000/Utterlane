package io.github.lrq3000.utterlane.archive

/** MediaCodec can concatenate many AMR storage frames in one output buffer. */
object AmrFrames {
    // Octet-aligned 3GPP storage sizes INCLUDING the TOC byte. Reserved frame
    // types are rejected; SID, SPEECH_LOST (WB) and NO_DATA still own 20 ms.
    private val nb = intArrayOf(13, 14, 16, 18, 20, 21, 27, 32, 6, -1, -1, -1, -1, -1, -1, 1)
    private val wb = intArrayOf(18, 24, 33, 37, 41, 47, 51, 59, 61, 6, -1, -1, -1, -1, 1, 1)
    fun split(buffer: ByteArray, sampleRate: Int, firstFrame: Long = 0, accept: (ByteArray, Long) -> Unit) {
        require(sampleRate == 8000 || sampleRate == 16000)
        require(buffer.size in 1..EncodedJournal.MAX_PACKET && firstFrame >= 0)
        val sizes = if (sampleRate == 8000) nb else wb
        fun size(offset: Int): Int {
            val toc = buffer[offset].toInt() and 255
            require(toc and 0x83 == 0) { "Invalid AMR storage TOC" }
            val n = sizes[toc ushr 3 and 15]
            require(n > 0 && offset + n <= buffer.size) { "Reserved or truncated AMR frame" }
            return n
        }
        // Validate the entire native buffer before exposing ANY coverage.
        var offset = 0
        while (offset < buffer.size) offset += size(offset)
        offset = 0
        var frame = firstFrame
        while (offset < buffer.size) {
            val n = size(offset)
            accept(buffer.copyOfRange(offset, offset + n), frame)
            frame += sampleRate / 50; offset += n
        }
    }
}
