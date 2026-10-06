package io.github.lrq3000.utterlane.onboarding

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.lrq3000.utterlane.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A normal audio share, with a private bundled source and a narrow read grant. */
class OnboardingSample(private val context: Context) {
    companion object {
        const val SHA256 = "7382d35e88949640179e233a32157d9fe85907e0342f6a22bd08ccef26e293f5"
        const val BYTES = 288044L
    }
    suspend fun shareIntent(): Intent = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "onboarding")
        check(directory.mkdirs() || directory.isDirectory)
        // A versioned cache name prevents an app update reusing a same-sized
        // excerpt from an older release after its bundled audio has changed.
        val target = File(directory, "alice-${SHA256.take(12)}.wav")
        if (!target.isFile || target.length() != BYTES) {
            val staging = File.createTempFile("sample-", ".wav", directory)
            try {
                context.assets.open("onboarding/alice_wonderland_excerpt.wav").use { input ->
                    staging.outputStream().use { output -> input.copyTo(output) }
                }
                // Publish only the complete file. Receivers may keep reading after
                // the chooser closes, so normal shares reuse the immutable cache copy.
                check(staging.renameTo(target))
            } finally { staging.delete() }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(context.getString(R.string.onboarding_sample_title), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
