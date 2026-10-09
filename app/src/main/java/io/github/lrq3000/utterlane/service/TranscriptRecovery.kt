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

/** Recovery is available even when a background caller cannot receive a large result. */
object TranscriptRecovery {
    fun show(context: Context, store: TranscriptStore) {
        UtterlaneApp.instance.applicationScope.launch(Dispatchers.IO) {
            if (store.file.length() == 0L || TranscriptSource.read(store.file).discarded) { store.dispose(); return@launch }
            store.attachSource(TranscriptSource.read(store.file).copy(recovered = true))
            store.keepForRecovery()
            // The Settings recovery action also works when notification permission is
            // denied. No transcription text is placed in the notification itself.
            context.getSharedPreferences("transcript_recovery", Context.MODE_PRIVATE).edit()
                .putString("path", store.file.absolutePath).apply()
            val request = store.file.name.hashCode() and Int.MAX_VALUE
            val intent = Intent(context, TranscribeActivity::class.java).putExtra("transcript_path", store.file.absolutePath)
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
