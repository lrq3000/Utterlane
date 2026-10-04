package io.github.lrq3000.utterlane.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.TranscriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.lrq3000.utterlane.asr.DeviceWakeObserver
import io.github.lrq3000.utterlane.asr.TranscriptCursor
import io.github.lrq3000.utterlane.asr.TranscriptionPower

/** Pinned delivery with one complete-session clipboard fallback, never a last-chunk overwrite. */
class StreamingTextTarget(
    context: Context,
    private val available: () -> Boolean = { true },
    private val insert: (String) -> Boolean
) {
    private val context = context.applicationContext
    private val cursor = TranscriptCursor()
    private val mutex = Mutex()
    private var store: TranscriptStore? = null
    private var closed = false
    private var finishing = false
    private var observer: DeviceWakeObserver? = null

    suspend fun accept(store: TranscriptStore) = mutex.withLock {
        if (closed || finishing) return@withLock
        this.store = store
        if (observer == null) observer = DeviceWakeObserver(context) { resume() }
        drain(store)
    }

    fun resume() {
        UtterlaneApp.instance.applicationScope.launch {
            mutex.withLock { if (!closed && !finishing) store?.let { drain(it) } }
        }
    }

    private suspend fun drain(store: TranscriptStore) {
        cursor.drain(store, { !closed && DeviceWakeObserver.canDeliver(context) && available() }) { text ->
            try { insert(text) } catch (e: RuntimeException) {
                android.util.Log.w("StreamingTextTarget", "Original editor is unavailable", e)
                false
            }
        }
    }

    /** Cancellation owns recovery elsewhere; just detach wake callbacks here. */
    fun close() { closed = true; observer?.close(); observer = null }

    fun sessionClosed() {
        // Normal completion has handed ownership to finish(). Cancellation has
        // not, and must invalidate even a delivery suspended in a disk read.
        if (!finishing) close()
        else { observer?.close(); observer = null }
    }

    fun finish(store: TranscriptStore?, preserve: Boolean = false) {
        if (store == null) { close(); return }
        if (finishing) return
        finishing = true
        UtterlaneApp.instance.applicationScope.launch {
            TranscriptionPower(context).use {
                mutex.withLock {
                    try {
                        drain(store)
                        if ((!cursor.hasPending(store) && !preserve) || store.file.length() == 0L) {
                            withContext(Dispatchers.IO) { store.dispose() }
                        } else {
                            // Recovery is published even if clipboard delivery is
                            // blocked by the lock screen or the clipboard throws.
                            TranscriptRecovery.show(context, store)
                            if (DeviceWakeObserver.canDeliver(context)) {
                                val text = withContext(Dispatchers.IO) { store.readForTransfer() }
                                if (text != null) {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Transcription", text))
                                    Toast.makeText(context, context.getString(R.string.toast_no_field_clipboard), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        TranscriptRecovery.show(context, store)
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("StreamingTextTarget", "Preserving undelivered transcript", e)
                        TranscriptRecovery.show(context, store)
                    } finally { close() }
                }
            }
        }
    }
}
