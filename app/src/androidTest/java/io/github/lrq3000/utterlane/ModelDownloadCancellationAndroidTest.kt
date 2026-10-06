package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ModelDownloadCancellationAndroidTest {
    @Test fun asynchronousDownloadRejectsCorruptionAndCanRetryWithoutPublishingPartialFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val good = "verified fixture".toByteArray()
        val body = AtomicReference(ByteArray(good.size))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.get().toResponseBody()).build()
        }.build()
        val hash = MessageDigest.getInstance("SHA-256").digest(good).joinToString("") { "%02x".format(it) }
        val model = ModelDefinition("onboarding-verified-${UUID.randomUUID()}", "Verified fixture", ModelBackend.CRISP,
            listOf(ModelArtifact("https://onboarding-verified.invalid/model", "model.gguf", good.size.toLong(), hash)))
        val manager = ModelManager(context, client, model)
        try {
            manager.downloadModel()
            assertEquals(ModelManager.ErrorType.CHECKSUM_MISMATCH, (manager.downloadState.value as ModelManager.DownloadState.Error).type)
            assertFalse(manager.directory().exists())
            body.set(good)
            manager.downloadModel()
            assertEquals(ModelManager.DownloadState.Ready, manager.downloadState.value)
            assertTrue(manager.ensureVerified())
            assertArrayEquals(good, java.io.File(manager.directory(), "model.gguf").readBytes())
        } finally {
            manager.directory().deleteRecursively()
            client.dispatcher.executorService.shutdown()
            assertTrue(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            client.connectionPool.evictAll()
        }
    }

    @Test fun cancellingDuringDnsReleasesTheTransferWithoutWaitingForTheResolver() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }
        }).build()
        val model = ModelDefinition("onboarding-cancel-test", "Cancellation fixture", ModelBackend.CRISP,
            listOf(ModelArtifact("https://onboarding-cancel.invalid/model", "model.gguf", 16, null)))
        val manager = ModelManager(context, client, model)
        val job = launch(Dispatchers.IO) { manager.downloadModel() }
        try {
            assertTrue("DNS was not reached", entered.await(5, TimeUnit.SECONDS))
            manager.cancelTransfer()
            // A deliberately stuck resolver reproduces the emulator failure
            // without depending on external network conditions or long timeouts.
            withTimeout(1500) { job.join() }
            assertFalse(manager.isTransferring)
            assertEquals(ModelManager.DownloadState.NotStarted, manager.downloadState.value)
            assertFalse(manager.directory().exists())
        } finally {
            release.countDown()
            job.cancelAndJoin()
            // Let the deliberately blocked DNS callback return after release;
            // interrupting its latch can escape OkHttp's IOException boundary.
            client.dispatcher.executorService.shutdown()
            assertTrue(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            client.connectionPool.evictAll()
            manager.directory().deleteRecursively()
        }
    }
}
