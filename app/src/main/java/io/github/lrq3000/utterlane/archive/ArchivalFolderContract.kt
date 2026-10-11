package io.github.lrq3000.utterlane.archive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import com.anggrayudi.storage.contract.OpenFolderPickerContract

/** SimpleStorage supplies the platform-aware picker intent. Preserve its raw SAF
 * result rather than the library's legacy DocumentFile/File path conversion and
 * root-permission heuristics. Grant persistence happens off Main in the caller. */
internal class ArchivalFolderContract(context: Context) : ActivityResultContract<Unit, Uri?>() {
    private val delegate = OpenFolderPickerContract(context)
    override fun createIntent(context: Context, input: Unit): Intent =
        delegate.createIntent(context, OpenFolderPickerContract.Options()).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
