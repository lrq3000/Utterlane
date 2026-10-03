package com.translander.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.translander.R
import com.translander.TranslanderApp
import com.translander.asr.TranscriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pinned delivery with one complete-session clipboard fallback, never a last-chunk overwrite. */
class StreamingTextTarget(private val context: Context, private val insert: (String) -> Boolean) {
    private var first = true
    private var failed = false
    fun accept(delta: String) {
        if (failed) return
        val text = (if (first) "" else " ") + delta
        failed = try { !insert(text) } catch (e: RuntimeException) {
            android.util.Log.w("StreamingTextTarget", "Original editor is unavailable", e)
            true
        }
        first = false
    }
    fun finish(store: TranscriptStore?, preserve: Boolean = false) {
        if (store == null) return
        TranslanderApp.instance.applicationScope.launch {
            if (!failed || store.file.length() == 0L) {
                withContext(Dispatchers.IO) { if (preserve && store.file.length() > 0) store.keepForRecovery() else store.dispose() }
                return@launch
            }
            val text = withContext(Dispatchers.IO) { store.readForTransfer() }
            if (text != null) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Transcription", text))
                Toast.makeText(context, context.getString(R.string.toast_no_field_clipboard), Toast.LENGTH_LONG).show()
            }
            TranscriptRecovery.show(context, store)
        }
    }
}
