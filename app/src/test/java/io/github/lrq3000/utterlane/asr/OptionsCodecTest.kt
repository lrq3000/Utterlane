package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import org.junit.Assert.*
import org.junit.Test

class OptionsCodecTest {
    @Test fun strictWireMapRoundTripsAnEntireSnapshotIncludingDisabledLimits() {
        val options = RuntimeOptions(workerConnectSeconds = 0, inferenceStallSeconds = 0, absoluteOperationSeconds = 12, asrThreads = 0)
        assertEquals(options, OptionsCodec.fromValues(options.toMap()))
    }

    @Test fun invalidSenderDataIsRejectedRatherThanSilentlyDefaulted() {
        for ((key, value) in listOf("prepare_stall_seconds" to "broken", "inference_stall_seconds" to "-1",
            "asr_threads" to "33", "asr_window_seconds" to "12", "native_update_frames" to "1024",
            "diagnostics" to "yes")) {
            assertThrows(IllegalArgumentException::class.java) { OptionsCodec.fromValues(RuntimeOptions().toMap() + (key to value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { OptionsCodec.fromValues(emptyMap<String, String>()) }
        assertThrows(IllegalArgumentException::class.java) { OptionsCodec.fromValues(RuntimeOptions().toMap() + ("asr_threads" to 4)) }
    }
}
