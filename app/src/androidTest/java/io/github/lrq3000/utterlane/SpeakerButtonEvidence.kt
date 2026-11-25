package io.github.lrq3000.utterlane

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Export only the button, never the recording, editor, or surrounding screen. */
internal fun saveSpeakerCrop(name: String, bounds: Rect) {
    if (InstrumentationRegistry.getArguments().getString("speakerVisualEvidence") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "speaker-visual").apply { mkdirs() }
        try { File(dir, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { crop.recycle() }
    } finally { screen.recycle() }
}
