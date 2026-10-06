package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DownloadOptionsTest {
    @Test fun downloadUsesOneTimeoutSnapshotAndRetainsInjectedClientBehavior() {
        var requests = 0
        val interceptor = Interceptor { chain ->
            requests++
            assertEquals(12000, chain.connectTimeoutMillis())
            assertEquals(34000, chain.readTimeoutMillis())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("verified bytes".toResponseBody()).build()
        }
        val base = OkHttpClient.Builder().addInterceptor(interceptor)
            .writeTimeout(17, TimeUnit.SECONDS).callTimeout(99, TimeUnit.SECONDS).build()
        var options = RuntimeOptions(downloadConnectSeconds = 12, downloadReadSeconds = 34)
        val transfer = ModelManager.clientForDownload(base, options)
        options = options.copy(downloadConnectSeconds = 56, downloadReadSeconds = 78)
        assertSame(base.dispatcher, transfer.dispatcher)
        assertSame(base.connectionPool, transfer.connectionPool)
        assertSame(interceptor, transfer.interceptors.single())
        assertEquals(17000, transfer.writeTimeoutMillis)
        assertEquals(99000, transfer.callTimeoutMillis)
        repeat(2) { index ->
            transfer.newCall(Request.Builder().url("https://example.invalid/artifact-$index").build()).execute().use {
                assertEquals("verified bytes", it.body!!.string())
            }
        }
        assertEquals(2, requests)
        val nextTransfer = ModelManager.clientForDownload(base, options)
        assertEquals(56000, nextTransfer.connectTimeoutMillis)
        assertEquals(78000, nextTransfer.readTimeoutMillis)
        assertEquals(10000, base.connectTimeoutMillis)
    }

    @Test fun defaultDisabledAndMaximumTimeoutsAreSupported() {
        val base = OkHttpClient()
        val defaults = ModelManager.clientForDownload(base, RuntimeOptions())
        assertEquals(30000, defaults.connectTimeoutMillis)
        assertEquals(60000, defaults.readTimeoutMillis)
        val disabled = ModelManager.clientForDownload(base, RuntimeOptions(downloadConnectSeconds = 0, downloadReadSeconds = 0))
        assertEquals(0, disabled.connectTimeoutMillis)
        assertEquals(0, disabled.readTimeoutMillis)
        val maximum = ModelManager.clientForDownload(base, RuntimeOptions(downloadConnectSeconds = 86400, downloadReadSeconds = 86400))
        assertEquals(86400000, maximum.connectTimeoutMillis)
        assertEquals(86400000, maximum.readTimeoutMillis)
    }

    @Test fun invalidTimeoutCannotReachOkHttp() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelManager.clientForDownload(OkHttpClient(), RuntimeOptions(downloadReadSeconds = -1))
        }
    }
}
