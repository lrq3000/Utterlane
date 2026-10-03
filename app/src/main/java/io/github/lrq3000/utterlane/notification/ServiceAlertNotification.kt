package io.github.lrq3000.utterlane.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.service.ServiceStartActivity

/**
 * Handles the service alert notification shown when FGS cannot start at boot.
 * On Android 14+, restricted FGS types (microphone, dataSync) cannot start from
 * BOOT_COMPLETED, so we show a notification prompting the user to tap.
 * Tapping launches an invisible trampoline activity that starts the services
 * and finishes immediately — the user never sees any UI.
 */
class ServiceAlertNotification(private val context: Context) {

    /**
     * Show a notification prompting the user to tap and start services.
     * Auto-cancels on tap for clean UX.
     *
     * @param messageResId String resource ID for the notification message
     */
    fun show(messageResId: Int) {
        val intent = Intent(context, ServiceStartActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, UtterlaneApp.SERVICE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(messageResId))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(UtterlaneApp.SERVICE_ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Dismiss the service alert notification.
     * Called after services have been successfully started.
     */
    fun dismiss() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(UtterlaneApp.SERVICE_ALERT_NOTIFICATION_ID)
    }
}
