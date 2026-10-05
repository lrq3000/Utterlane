package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlin.math.ceil

/** All capture allocations are derived once from the same validated session snapshot. */
internal class CaptureBufferPolicy(options: RuntimeOptions) {
    private val snapshot = options.requireValid()
    val blockSamples = AudioRecorder.SAMPLE_RATE * snapshot.captureBlockMs / 1000
    val maximumSamples = AudioRecorder.SAMPLE_RATE * snapshot.queueSeconds

    // Keep the historical 1000-object/20-second allowance for partial reads, but
    // also fit a full time budget at faster configured block cadences. A sample
    // limit alone would allow millions of one-sample arrays; an object limit alone
    // could exceed the PCM memory budget with large blocks. Both are necessary.
    val queueCapacity = maxOf(snapshot.queueSeconds * 50, (maximumSamples + blockSamples - 1) / blockSamples)

    fun recorderBufferBytes(androidMinimum: Int): Int = maxOf(
        androidMinimum, ceil(snapshot.captureBufferSeconds * AudioRecorder.SAMPLE_RATE).toInt() * Short.SIZE_BYTES
    )
}
