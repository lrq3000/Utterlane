package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureSilencingTest {
    @Test fun callbacksCannotQueryOrPublishRecorderStateOffTheCaptureWorker() {
        var queries = 0
        val published = mutableListOf<Boolean>()
        val monitor = CaptureSilencing { published.add(it) }
        val invalidate = monitor.opened { queries++; true }
        val before = published.toList()
        val callback = Thread { invalidate() }
        callback.start(); callback.join(2000)
        assertEquals("A platform callback must only invalidate its own generation", 0, queries)
        assertEquals(before, published)
        assertTrue(monitor.poll())
        assertEquals(listOf(true), published)
    }

    @Test fun lateOldRecorderCallbackCannotSilenceOrQueryItsReplacement() {
        var newQueries = 0
        val monitor = CaptureSilencing {}
        val oldCallback = monitor.opened { true }
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val callback = Thread { entered.countDown(); resume.await(2, TimeUnit.SECONDS); oldCallback() }
        callback.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            monitor.opened { newQueries++; false }
            assertFalse(monitor.poll())
            assertEquals(1, newQueries)
            resume.countDown(); callback.join(2000)
            assertFalse(monitor.poll())
            assertEquals("An obsolete event must not invalidate the new recorder", 1, newQueries)
        } finally { resume.countDown(); callback.join(2000) }
    }
}
