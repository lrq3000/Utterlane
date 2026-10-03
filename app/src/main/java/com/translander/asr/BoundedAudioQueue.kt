package com.translander.asr

import kotlinx.coroutines.channels.Channel

/** At most 100 immutable 200 ms blocks: a finite twenty-second live backlog. */
class BoundedAudioQueue(capacity: Int = 100) {
    val blocks = Channel<ShortArray>(capacity)
    fun offer(samples: ShortArray): Boolean {
        require(samples.size in 1..3200)
        return blocks.trySend(samples).isSuccess
    }
    fun close() = blocks.close()
}
