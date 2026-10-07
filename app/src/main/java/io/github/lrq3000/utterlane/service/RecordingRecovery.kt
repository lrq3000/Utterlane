package io.github.lrq3000.utterlane.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** One recovery route for keyboard, overlay, accessibility, onboarding and speech APIs. */
object RecordingRecovery {
    private const val NOTIFICATION = 1010
    fun intent(context: Context, id: String): Intent = Intent(context, TranscribeActivity::class.java)
        .setData(Uri.parse("utterlane://recording-recovery/$id"))
        .putExtra(TranscribeActivity.EXTRA_RECORDING_RECOVERY, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun show(context: Context, id: String) {
        // Android can reject background activity launches without throwing. The
        // content-free notification and Settings action preserve an explicit route.
        val request = intent(context, id)
        val pending = PendingIntent.getActivity(context, 0, request,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, NOTIFICATION,
                NotificationCompat.Builder(context, UtterlaneApp.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_utterlane_notification)
                    .setContentTitle(context.getString(R.string.recording_recover_title))
                    .setContentText(context.getString(R.string.recording_recover_notification))
                    .setContentIntent(pending).setAutoCancel(true).build())
        } catch (e: SecurityException) { Log.w("RecordingRecovery", "Recovery notification unavailable", e) }
        open(context, id)
    }

    fun open(context: Context, id: String) {
        try { context.startActivity(intent(context, id)) }
        catch (e: Exception) { Log.w("RecordingRecovery", "Open the saved recording from Settings", e) }
    }

    fun dismissNotification(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION)
    }

    fun discard(context: Context, id: String) {
        dismissNotification(context, id)
        UtterlaneApp.instance.applicationScope.launch(Dispatchers.IO) {
            UtterlaneApp.instance.microphoneRecordings.discard(id)
        }
    }
}
