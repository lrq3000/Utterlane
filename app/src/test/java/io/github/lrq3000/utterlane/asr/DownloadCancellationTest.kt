package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadCancellationTest {
    @Test fun parentCancellationInterruptsExecuteWithNoReadTimeout() = checkBlockingCancellation(sendHeaders = false)

    @Test fun parentCancellationInterruptsBodyAfterExecuteHasReturned() = checkBlockingCancellation(sendHeaders = true)

    private fun checkBlockingCancellation(sendHeaders: Boolean) = runBlocking {
        val server = StalledResponse(sendHeaders)
        val call = ModelManager.clientForDownload(OkHttpClient(), RuntimeOptions(downloadReadSeconds = 0))
            .newCall(Request.Builder().url(server.url).build())
        val owner = Job()
        val readingBody = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = CoroutineScope(owner + Dispatchers.IO).launch {
            try {
                ModelManager.openDownload(call) {}.use { input ->
                    assertEquals('a'.code, input.read())
                    readingBody.countDown()
                    input.read() // The second byte never arrives without cancellation.
                }
            } catch (_: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                throw AssertionError("Unexpected network failure before cancellation")
            } finally { finished.countDown() }
        }
        try {
            assertTrue("The server must receive the request", server.requestReceived.await(3, TimeUnit.SECONDS))
            if (sendHeaders) assertTrue("Body read must start after execute returns", readingBody.await(3, TimeUnit.SECONDS))
            owner.cancel() // Simulates disposal of the caller's Compose scope, not cancelTransfer().
            assertTrue("Cancellation must reach Call.cancel while the worker is blocked", call.isCanceled())
            assertTrue("Blocking I/O must unwind so transfer finally can run", finished.await(3, TimeUnit.SECONDS))
            withTimeout(3000) { worker.join() }
            assertTrue(worker.isCancelled)
        } finally {
            // Also makes the RED run bounded: release sockets even when cancellation is broken.
            call.cancel()
            server.close()
            owner.cancel()
            withTimeout(3000) { worker.join() }
        }
    }

    @Test fun cancellationDuringActiveCallPublicationPreventsExecute() = runBlocking {
        var executions = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            executions++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("a".toResponseBody()).build()
        }.build()
        val call = client.newCall(Request.Builder().url("https://example.invalid/model").build())
        val owner = Job()
        val worker = CoroutineScope(owner + Dispatchers.IO).launch {
            ModelManager.openDownload(call) { owner.cancel() }.use { it.read() }
        }
        try {
            withTimeout(3000) { worker.join() }
            assertTrue("The hook must capture this call before publishing it", call.isCanceled())
            assertEquals("Cancelled work must not execute an interceptor", 0, executions)
        } finally { owner.cancel(); call.cancel() }
    }

    @Test fun closingResponseDetachesItsCancellationHook() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("a".toResponseBody()).build()
        }.build()
        val call = client.newCall(Request.Builder().url("https://example.invalid/model").build())
        val owner = Job()
        val closed = CompletableDeferred<Unit>()
        val worker = CoroutineScope(owner + Dispatchers.IO).launch {
            ModelManager.openDownload(call) {}.use { assertEquals('a'.code, it.read()) }
            closed.complete(Unit)
            awaitCancellation()
        }
        try {
            withTimeout(3000) { closed.await() }
            owner.cancel()
            withTimeout(3000) { worker.join() }
            assertFalse("Completed responses must not retain the owner hook", call.isCanceled())
        } finally { owner.cancel(); call.cancel() }
    }

    /** Real blocking OkHttp socket reads, with no dependency on Android or remote services. */
    private class StalledResponse(private val sendHeaders: Boolean) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        @Volatile private var socket: Socket? = null
        private val release = CountDownLatch(1)
        val requestReceived = CountDownLatch(1)
        val url = "http://127.0.0.1:${listener.localPort}/model"
        private val thread = Thread {
            try {
                listener.accept().use { accepted ->
                    socket = accepted
                    val reader = accepted.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    if (sendHeaders) {
                        accepted.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\na".toByteArray())
                            flush()
                        }
                    }
                    requestReceived.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            } catch (_: java.io.IOException) { /* Test teardown closes a blocked accept/socket. */ }
        }.apply { isDaemon = true; start() }

        override fun close() {
            release.countDown()
            socket?.close()
            listener.close()
            thread.join(3000)
        }
    }
}
