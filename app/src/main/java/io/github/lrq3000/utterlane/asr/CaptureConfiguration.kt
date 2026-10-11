package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.archive.PcmFormat
import io.github.lrq3000.utterlane.settings.RuntimeOptions

/** One operation's construction policy, reused by prepare and every reopen. */
internal class CaptureConfiguration(val format: PcmFormat, val options: RuntimeOptions, minimumBytes: Int) {
    val buffers = CaptureBufferPolicy(options, format)
    private val recorderBytes = buffers.recorderBufferBytes(minimumBytes)
    fun <T> create(factory: (PcmFormat, Int) -> T): T = factory(format, recorderBytes)
}
