package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicInteger

/** Both block count and sample budget are bounded, independent of capture cadence. */
class BoundedAudioQueue(capacity: Int = 1000, private val maximumSamples: Int = 320000) {
    constructor(options: RuntimeOptions) : this(CaptureBufferPolicy(options))
    private constructor(buffers: CaptureBufferPolicy) : this(buffers.queueCapacity, buffers.maximumSamples)

    private val channel = Channel<ShortArray>(capacity)
    private val samples = AtomicInteger(0)
    val blocks: ReceiveChannel<ShortArray> = object : ReceiveChannel<ShortArray> by channel {
        override suspend fun receive(): ShortArray = channel.receive().also { samples.addAndGet(-it.size) }
        override fun iterator(): ChannelIterator<ShortArray> {
            val delegate = channel.iterator()
            return object : ChannelIterator<ShortArray> {
                override suspend fun hasNext(): Boolean = delegate.hasNext()
                override fun next(): ShortArray = delegate.next().also { samples.addAndGet(-it.size) }
            }
        }
    }
    fun offer(data: ShortArray): Boolean {
        require(data.size in 1..3200)
        if (samples.addAndGet(data.size) > maximumSamples) { samples.addAndGet(-data.size); return false }
        if (!channel.trySend(data).isSuccess) { samples.addAndGet(-data.size); return false }
        return true
    }
    fun close() = channel.close()
}
