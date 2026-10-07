package io.github.lrq3000.utterlane.history

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.SettingsActivity

/** Audio recovery is discoverable in Settings even without notification permission. */
object RecordingRecovery {
    const val EXTRA_RECOVERY = "open_recording_recovery"
    const val EXTRA_MODELS = "open_model_settings"
    private const val NOTIFICATION_ID = 1004

    fun show(context: Context) {
        val intent = Intent(context, SettingsActivity::class.java).putExtra(EXTRA_RECOVERY, true)
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                NotificationCompat.Builder(context, UtterlaneApp.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_utterlane_notification)
                    .setContentTitle(context.getString(R.string.recording_recovery_title))
                    .setContentText(context.getString(R.string.recording_recovery_saved))
                    .setContentIntent(pending).setAutoCancel(true).build())
        } catch (e: SecurityException) { Log.i("RecordingRecovery", "Notification unavailable; audio recovery remains in Settings", e) }
    }

    fun openModels(context: Context) = context.startActivity(Intent(context, SettingsActivity::class.java)
        .putExtra(EXTRA_MODELS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
