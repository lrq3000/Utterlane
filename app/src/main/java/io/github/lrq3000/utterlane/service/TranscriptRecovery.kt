package io.github.lrq3000.utterlane.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Recovery is available even when a background caller cannot receive a large result. */
object TranscriptRecovery {
    fun show(context: Context, store: TranscriptStore, onSaved: (() -> Unit)? = null) {
        UtterlaneApp.instance.applicationScope.launch(Dispatchers.IO) {
            if (store.file.length() == 0L || TranscriptSource.read(store.file).discarded) { store.dispose(); return@launch }
            val app = UtterlaneApp.instance
            val source = TranscriptSource.read(store.file).copy(recovered = true)
            val queue = app.transcriptionQueue
            val recovered = if (store.file.parentFile == queue.resultsDirectory) {
                // Settings backgrounding uses the normal queue review route. For ordinary
                // undelivered dictation, place its recovery in the existing private cache
                // rather than broadening the exported transcription activity's path checks.
                if (queue.ownsWorkingText(store.file)) { store.keepForRecovery(); return@launch }
                try {
                    TranscriptSource.withActiveSource(store.file) {
                        val copy = store.snapshot(File(context.cacheDir, "transcripts"))
                        if (onSaved != null) RandomAccessFile(copy, "rw").use { it.fd.sync() }
                        source.write(copy)
                        store.dispose()
                        copy
                    }
                } catch (error: Exception) {
                    android.util.Log.w("TranscriptRecovery", "Keeping original keyboard text after recovery copy failure", error)
                    store.keepForRecovery()
                    source.audioId?.let { io.github.lrq3000.utterlane.history.RecordingRecovery.show(context, it) }
                    return@launch
                }
            } else {
                store.attachSource(source)
                store.keepForRecovery()
                store.file
            }
            // The Settings recovery action also works when notification permission is
            // denied. No transcription text is placed in the notification itself.
            val entry = context.getSharedPreferences("transcript_recovery", Context.MODE_PRIVATE).edit()
                .putString("path", recovered.absolutePath)
            if (onSaved == null) entry.apply()
            else if (entry.commit()) onSaved()
            val request = recovered.name.hashCode() and Int.MAX_VALUE
            val intent = Intent(context, TranscribeActivity::class.java).putExtra("transcript_path", recovered.absolutePath)
            val pending = PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            withContext(Dispatchers.Main) {
                context.getSystemService(NotificationManager::class.java).notify(request,
                    NotificationCompat.Builder(context, UtterlaneApp.NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_utterlane_notification).setContentTitle(context.getString(R.string.stream_recover))
                        .setContentText(context.getString(R.string.stream_preview)).setContentIntent(pending).setAutoCancel(true).build())
            }
        }
    }

    fun open(context: Context) {
        UtterlaneApp.instance.applicationScope.launch(Dispatchers.IO) {
            val path = context.getSharedPreferences("transcript_recovery", Context.MODE_PRIVATE).getString("path", null)
            val file = path?.let { java.io.File(it) }
            val available = file?.isFile == true && !TranscriptSource.read(file).discarded
            withContext(Dispatchers.Main) {
                if (available) context.startActivity(Intent(context, TranscribeActivity::class.java)
                    .putExtra("transcript_path", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else android.widget.Toast.makeText(context, context.getString(R.string.stream_recover_none), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
