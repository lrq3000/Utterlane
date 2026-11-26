package io.github.lrq3000.utterlane.asr

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ModelResumeTest {
    @Test fun newManagerResumesFromTheLocalPrefix() = runBlocking {
        fixture(
            { interrupted(it, "abc") },
            { response(it, "def".toResponseBody(), 206, "bytes 3-5/6") }
        ).use { f ->
            f.manager().downloadModel()
            assertFalse(f.manager().directory().exists())

            val retry = f.manager()
            retry.downloadModel()
            assertEquals("bytes=3-", f.requests[1].header("Range"))
            assertNull(f.requests[1].header("If-Range")) // A pinned digest, not a CDN ETag, identifies these bytes.
            assertEquals("identity", f.requests[1].header("Accept-Encoding"))
            assertEquals(ModelManager.DownloadState.Ready, retry.downloadState.value)
            assertEquals("abcdef", retry.directory().resolve("a.gguf").readText())
        }
    }

    @Test fun interruptionAndWaitingForRetryHeadersKeepSavedProgressVisible() = runBlocking {
        val waiting = CountDownLatch(1)
        val reply = CountDownLatch(1)
        fixture(
            { interrupted(it, "abc") },
            { request ->
                waiting.countDown()
                check(reply.await(8, TimeUnit.SECONDS))
                response(request, "def".toResponseBody(), 206, "bytes 3-5/6", tag = "\"different-cdn-tag\"")
            }
        ).use { f ->
            val manager = f.manager()
            manager.downloadModel()
            val failure = manager.downloadState.value as ModelManager.DownloadState.Error
            assertEquals(ModelManager.ErrorType.NETWORK, failure.type)
            assertEquals(50, failure.savedProgress)
            val completion = async(Dispatchers.IO) { manager.downloadModel() }
            try {
                assertTrue(waiting.await(8, TimeUnit.SECONDS))
                assertEquals(ModelManager.DownloadState.Downloading(50, connecting = true), manager.downloadState.value)
            } finally { reply.countDown(); completion.await() }
            assertEquals(listOf(null, "bytes=3-"), f.requests.map { it.header("Range") })
            assertNull(f.requests[1].header("If-Range"))
            assertTrue(manager.ensureVerified())
        }
    }

    @Test fun aResumedSuffixWithWrongPinnedDigestIsNeverInstalled() = runBlocking {
        fixture(
            { interrupted(it, "abc") },
            { response(it, "BAD".toResponseBody(), 206, "bytes 3-5/6", tag = "\"different-cdn-tag\"") }
        ).use { f ->
            val manager = f.manager()
            manager.downloadModel()
            manager.downloadModel()
            assertEquals(listOf(null, "bytes=3-"), f.requests.map { it.header("Range") })
            assertEquals(ModelManager.ErrorType.CHECKSUM_MISMATCH, (manager.downloadState.value as ModelManager.DownloadState.Error).type)
            assertFalse(manager.directory().exists())
        }
    }

    @Test fun aFullResponseReplacesRatherThanAppendsThePrefix() = runBlocking {
        fixture(
            { interrupted(it, "xxx") },
            { response(it, "abcdef".toResponseBody(), tag = "\"v2\"") }
        ).use { f ->
            val manager = f.manager()
            manager.downloadModel()
            manager.downloadModel()
            assertEquals("bytes=3-", f.requests[1].header("Range"))
            assertEquals("abcdef", manager.directory().resolve("a.gguf").readText())
        }
    }

    @Test fun wrongContentRangeTriggersOneCleanRequest() = runBlocking {
        fixture(
            { interrupted(it, "abc") },
            { response(it, "ZZZ".toResponseBody(), 206, "bytes 1-3/6") },
            { response(it, "abcdef".toResponseBody()) }
        ).use { f ->
            val manager = f.manager()
            manager.downloadModel()
            manager.downloadModel()
            assertEquals(3, f.requests.size)
            assertEquals("bytes=3-", f.requests[1].header("Range"))
            assertNull(f.requests[2].header("Range"))
            assertEquals("abcdef", manager.directory().resolve("a.gguf").readText())
        }
    }

    @Test fun completedArtifactIsReusedWhenTheNextFileFails() = runBlocking {
        fixture(
            { response(it, "abcdef".toResponseBody()) },
            { interrupted(it, "to") },
            { response(it, "ken".toResponseBody(), 206, "bytes 2-4/5") },
            includeSecond = true
        ).use { f ->
            val manager = f.manager()
            manager.downloadModel()
            assertFalse(manager.directory().exists())
            manager.downloadModel()
            assertEquals(listOf("/a.gguf", "/tokens.txt", "/tokens.txt"), f.requests.map { it.url.encodedPath })
            assertEquals("bytes=2-", f.requests[2].header("Range"))
            assertEquals("token", manager.directory().resolve("tokens.txt").readText())
        }
    }

    @Test fun unsignedArtifactCannotAppendWithoutARepeatedStrongValidator() = runBlocking {
        fixture(
            { response(it, "abcdef".toResponseBody()) },
            { interrupted(it, "to") },
            { response(it, "BAD".toResponseBody(), 206, "bytes 2-4/5", tag = null) },
            { response(it, "token".toResponseBody()) },
            includeSecond = true
        ).use { f ->
            val manager = f.manager()
            manager.downloadModel()
            manager.downloadModel()
            assertEquals(listOf(null, null, "bytes=2-", null), f.requests.map { it.header("Range") })
            assertEquals("token", manager.directory().resolve("tokens.txt").readText())
        }
    }

    @Test fun explicitCancelDoesNotKeepARetryCheckpoint() = runBlocking {
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture(
            { request -> interrupted(request, "abc") { read.countDown(); check(release.await(8, TimeUnit.SECONDS)) } },
            { response(it, "abcdef".toResponseBody()) }
        ).use { f ->
            val manager = f.manager()
            val task = launch(Dispatchers.IO) { manager.downloadModel() }
            try {
                assertTrue(read.await(8, TimeUnit.SECONDS))
                manager.cancelTransfer()
            } finally {
                release.countDown()
                task.join()
            }
            assertFalse(manager.directory().exists())
            manager.downloadModel()
            assertEquals(2, f.requests.size)
            assertNull(f.requests[1].header("Range"))
            assertTrue(manager.ensureVerified())
        }
    }

    private fun interrupted(request: Request, prefix: String, beforeFailure: () -> Unit = {}): Response =
        response(request, object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = if (request.url.encodedPath == "/tokens.txt") 5L else 6L
            private val data = Buffer().writeUtf8(prefix)
            private val reader = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (data.size > 0) return data.read(sink, byteCount)
                    beforeFailure()
                    throw IOException("Connection lost")
                }
                override fun close() = Unit
                override fun timeout() = Timeout.NONE
            }.buffer()
            override fun source() = reader
        })

    private fun response(request: Request, body: ResponseBody, code: Int = 200, range: String? = null, tag: String? = "\"v1\"") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .apply { tag?.let { header("ETag", it) }; range?.let { header("Content-Range", it) } }.body(body).build()

    private fun fixture(vararg responses: (Request) -> Response, includeSecond: Boolean = false) = Fixture(responses, includeSecond)

    private class Fixture(responses: Array<out (Request) -> Response>, includeSecond: Boolean) : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val model = ModelDefinition("resume-test-${UUID.randomUUID()}", "Resume test", ModelBackend.CRISP, buildList {
            val hash = MessageDigest.getInstance("SHA-256").digest("abcdef".toByteArray()).joinToString("") { "%02x".format(it) }
            add(ModelArtifact("https://resume.invalid/a.gguf", "a.gguf", 6, hash))
            if (includeSecond) add(ModelArtifact("https://resume.invalid/tokens.txt", "tokens.txt", 5, null))
        })
        val requests = mutableListOf<Request>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val index = requests.size
            requests += request
            responses.getOrNull(index)?.invoke(request) ?: throw IOException("Unexpected request")
        }.build()
        fun manager() = ModelManager(context, client, model)
        override fun close() {
            val target = manager().directory()
            target.deleteRecursively()
            target.parentFile?.listFiles().orEmpty().filter { model.id in it.name }.forEach { it.deleteRecursively() }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
