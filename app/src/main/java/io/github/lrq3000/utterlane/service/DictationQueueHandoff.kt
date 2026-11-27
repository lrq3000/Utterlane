package io.github.lrq3000.utterlane.service

import android.content.Context
import android.util.Log
import android.widget.Toast
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.QueueTranscriptTarget
import io.github.lrq3000.utterlane.asr.SessionFailure
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.RecordingRecovery
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Main-confined ownership choice. The producer and its transcript path never change across handoff. */
internal class DictationQueueHandoff(
    private val scope: CoroutineScope,
    private val file: File?,
    private val register: suspend (String, Boolean) -> Unit,
    private val finish: suspend (String?, Boolean?) -> Unit,
    private val isRegistered: (String) -> Boolean = { false },
    private val track: (Job) -> Unit = {}
) {
    val producerScope = if (file == null) scope else CoroutineScope(scope.coroutineContext + QueueTranscriptTarget(file))
    private var publication: CompletableDeferred<Boolean>? = null
    private var completed = false
    val ownsNavigation: Boolean get() = publication != null

    fun submit(audio: String, speakers: Boolean, accepted: () -> Unit, failed: (Exception) -> Unit): Boolean {
        if (publication != null || completed) return false
        val result = CompletableDeferred<Boolean>()
        publication = result
        scope.launch {
            try { register(audio, speakers) }
            catch (error: Exception) {
                // A notification or service failure after the durable row was written cannot hand its files back to the editor.
                // Failed initialization can also make the ownership lookup unreadable. Resolve the pending choice so ordinary delivery can finish.
                val registered = try { isRegistered(audio) } catch (lookup: Exception) {
                    if (lookup !== error) error.addSuppressed(lookup)
                    false
                }
                if (!registered) {
                    publication = null
                    result.complete(false)
                    if (error is CancellationException) throw error
                    failed(error)
                    return@launch
                }
            }
            try { accepted() } finally { result.complete(true) }
        }.also(track)
        return true
    }

    fun complete(store: TranscriptStore?, error: String?, speakers: Boolean?, editor: () -> Unit): Job {
        check(!completed)
        completed = true
        val choice = publication
        if (choice == null) editor() // Mark normal delivery as started before the session's close callback.
        return scope.launch {
            if (choice == null) return@launch
            if (!choice.await()) { editor(); return@launch }
            try { finish(error, speakers) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // The durable DRAINING row and transcript can still be repaired on cold start.
                Log.e("DictationQueueHandoff", "Could not finalize queued dictation ${file?.name}", failure)
            } finally { store?.keepForRecovery() }
        }.also(track)
    }

    fun interrupted(error: String): Job? =
        if (!completed && ownsNavigation) complete(null, error, null) {} else null

    companion object {
        fun create(context: Context): DictationQueueHandoff {
            val app = UtterlaneApp.instance
            val textId = UUID.randomUUID().toString()
            val queue = app.transcriptionQueue
            val title = context.getString(R.string.dialog_title)
            // An unavailable queue directory must not prevent an ordinary take.
            val file = runCatching { queue.target(textId) }
            return DictationQueueHandoff(app.applicationScope, file.getOrNull(),
                register = { audio, speakers ->
                    file.getOrThrow()
                    queue.captureEnded(textId, audio, title, speakers)
                    queue.wake()
                },
                finish = { error, speakers -> queue.completeCapture(textId, error, speakers) },
                isRegistered = { audio -> queue.journal.find(textId)?.audio == audio },
                track = { queue.trackPublication(it) })
        }
    }
}

/** Only the continued-in-background route keeps a successful temporary source for review. */
internal fun finishForegroundDictation(context: Context, audio: String?, failure: SessionFailure?, openRecovery: Boolean = true) {
    val app = UtterlaneApp.instance
    if (failure == null || failure.kind == SessionFailure.Kind.NO_SPEECH) {
        if (audio != null) app.applicationScope.launch(Dispatchers.IO) {
            try { app.recordingHistory.dismiss(audio) }
            catch (error: Exception) { Log.w("DictationQueueHandoff", "Temporary audio cleanup failed", error) }
        }
    }
    failure?.recoveryId?.let {
        RecordingRecovery.show(context, it)
        if (openRecovery) RecordingRecovery.open(context, it)
    }
}

internal fun notifyDictationQueueFailure(context: Context, error: Exception) {
    Log.w("DictationQueueHandoff", "Could not queue dictation", error)
    Toast.makeText(context, R.string.dictation_settings_queue_failed, Toast.LENGTH_LONG).show()
}
