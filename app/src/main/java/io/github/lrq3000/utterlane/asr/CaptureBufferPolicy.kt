package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlin.math.ceil

/** All capture allocations are derived once from the same validated session snapshot. */
internal class CaptureBufferPolicy(options: RuntimeOptions, format: io.github.lrq3000.utterlane.archive.PcmFormat = io.github.lrq3000.utterlane.archive.PcmFormat()) {
    private val snapshot = options.requireValid()
    private val samplesPerSecond = format.sampleRate * format.channels
    val blockSamples = (samplesPerSecond * snapshot.captureBlockMs / 1000).coerceAtMost(3200).let { it - it % format.channels }
    val maximumSamples = samplesPerSecond * snapshot.queueSeconds

    // Keep the historical 1000-object/20-second allowance for partial reads, but
    // also fit a full time budget at faster configured block cadences. A sample
    // limit alone would allow millions of one-sample arrays; an object limit alone
    // could exceed the PCM memory budget with large blocks. Both are necessary.
    val queueCapacity = maxOf(snapshot.queueSeconds * 50, (maximumSamples + blockSamples - 1) / blockSamples)

    fun recorderBufferBytes(androidMinimum: Int): Int = maxOf(
        androidMinimum, ceil(snapshot.captureBufferSeconds * samplesPerSecond).toInt() * Short.SIZE_BYTES
    )
}
