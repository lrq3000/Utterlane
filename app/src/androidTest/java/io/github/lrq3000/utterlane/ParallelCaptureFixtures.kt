package io.github.lrq3000.utterlane

import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.ModelCatalog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*

/** Shared, public-domain speech and hash-pinned model setup for isolated capture QA. */
internal object ParallelCaptureFixtures {
    suspend fun installTernary(app: UtterlaneApp) = withContext(Dispatchers.IO) {
        assertTrue(app.packageName.endsWith(".parallelcapture"))
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, "android.permission.READ_EXTERNAL_STORAGE")
        val source = File("/sdcard/Download/parakeet-qa/parakeet-redux-0.6b-TQ1_Q8_0.gguf")
        assertTrue("Provide the hash-pinned native ternary model fixture", source.isFile)
        val target = File(app.modelManager.directory(ModelCatalog.REDUX_TERNARY).apply { mkdirs() }, "model.gguf")
        if (!target.exists()) source.copyTo(target)
        // The production ModelManager performs size/SHA-256 verification before loading.
    }

    fun speech(app: UtterlaneApp): ShortArray {
        val bytes = app.assets.open("onboarding/alice_wonderland_excerpt.wav").use { it.readBytes() }
        return ShortArray((bytes.size - 44) / 2).also {
            ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
        }
    }
}
