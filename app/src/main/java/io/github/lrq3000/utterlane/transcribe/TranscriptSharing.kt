package io.github.lrq3000.utterlane.transcribe

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.TranscriptStore
import kotlinx.coroutines.*

/** Export snapshots outlive dialog dismissal without exposing the mutable working file. */
fun shareTranscript(context: Context, store: TranscriptStore) {
    val lease = store.acquire()
    UtterlaneApp.instance.applicationScope.launch {
        try {
            val file = withContext(Dispatchers.IO) { store.snapshot() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Transcript", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.transcribe_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionFeedback.show(context, R.string.action_feedback_share)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            android.util.Log.e("TranscribeActivity", "Transcript export failed", e)
            android.widget.Toast.makeText(context, e.message, android.widget.Toast.LENGTH_LONG).show()
        } finally { withContext(NonCancellable + Dispatchers.IO) { lease.close() } }
    }
}
