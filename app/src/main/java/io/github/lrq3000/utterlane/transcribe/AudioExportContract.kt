package io.github.lrq3000.utterlane.transcribe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

data class AudioExportRequest(val name: String, val mimeType: String, val directory: Boolean)

/** MIME travels with this invocation, not with a recomposed current history row. */
class AudioExportContract : ActivityResultContract<AudioExportRequest, Uri?>() {
    override fun createIntent(context: Context, input: AudioExportRequest) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mimeType).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
