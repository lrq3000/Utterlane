package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptionSessionLifecycleTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun cancelledSessionDoesNotDecodeOrPublishItsBufferedTail(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        var closes = 0
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()),
            onSegment = { fail("Cancelled session published text") },
            decode = { error("Cancelled session decoded audio") }, onClosed = { closes++ })
        try {
            session.accept(ShortArray(16000))
            session.close()
            session.finish()
            session.close()
            assertEquals(1, closes)
            assertEquals(0, store.segments)
            assertThrows(IllegalStateException::class.java) { runBlocking { session.accept(shortArrayOf(1)) } }
        } finally { session.close(); store.dispose() }
    }
}
