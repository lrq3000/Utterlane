package io.github.lrq3000.utterlane.transcribe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp

/** These are user-tapped recovery routes, not background Activity launches. Tagged IDs cannot replace a transcript result's own notification. */
internal class ImportRecoveryNotice(private val app: UtterlaneApp) {
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val channel = "import_recovery"
    private fun post(tag: String, text: String, target: Intent) {
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(channel, app.getString(R.string.queue_title), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 0, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = NotificationCompat.Builder(app, channel).setSmallIcon(R.drawable.ic_utterlane_notification)
            .setContentTitle(app.getString(R.string.queue_title)).setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build()
        try { manager.notify(tag, 1, notice) } catch (_: SecurityException) { /* Home exposes the same action without notifications. */ }
    }
    fun batch(item: ImportReceipt) = post(item.id, app.getString(R.string.queue_batch_action_required, item.accepted, item.total),
        FileTranscriptionSetupActivity.resume(app, item.id).setData(Uri.parse("utterlane://import/${item.id}")))
    fun clearBatch(id: String) = manager.cancel(id, 1)
    fun waiting(row: QueueRecord) = post("resume-queue", app.getString(R.string.queue_service_pending), QueueNotifications.intent(app, row.id))
    fun clearWaiting() = manager.cancel("resume-queue", 1)
}
