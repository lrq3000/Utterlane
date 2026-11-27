package io.github.lrq3000.utterlane.transcribe

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.service.WorkQuitReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class QueueNotifications(private val app: UtterlaneApp) {
    companion object {
        const val JOB = "transcription_queue_job"
        private const val CHANNEL = "transcription_jobs"
        fun intent(context: Context, id: String) = Intent(context, TranscribeActivity::class.java)
            .setData(Uri.Builder().scheme("utterlane").authority("transcription").appendPath(id).build()).putExtra(JOB, id)
            .putExtra(io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true)
    }
    private val manager = app.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.queue_title), NotificationManager.IMPORTANCE_LOW))
    }
    private fun builder(): NotificationCompat.Builder =
        NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_utterlane_notification)
            .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    fun serviceNotification(): Notification = WorkQuitReceiver.addAction(app, builder().setContentTitle(app.getString(R.string.queue_title))
        .setContentText(app.getString(R.string.queue_preparing_service)).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(app, 1031, HomeActivity.intent(app), PendingIntent.FLAG_IMMUTABLE))).build()
    fun notification(row: QueueRecord, percent: Int? = null): Notification {
        val open = PendingIntent.getActivity(app, 0, intent(app, row.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = app.getString(row.stage.label())
        val active = row.stage.needsProcessingService || row.stage == QueueStage.CAPTURING
        val notification = builder().setContentTitle(row.label).setContentText(if (percent == null) text else "$text · $percent%")
            .setContentIntent(open).setOngoing(active).setAutoCancel(false)
        if (active) {
            notification.setProgress(100, percent ?: 0, percent == null)
            WorkQuitReceiver.addAction(app, notification)
        }
        return notification.build()
    }
    fun post(row: QueueRecord, percent: Int? = null) = manager.notify(row.notificationId, notification(row, percent))
    fun publish(row: QueueRecord): Boolean = try {
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) false
        else { post(row); true }
    } catch (e: RuntimeException) {
        android.util.Log.w("TranscriptionQueue", "Result notification unavailable; delivery stays pending", e)
        false
    }
    fun remove(row: QueueRecord) = manager.cancel(row.notificationId)
}

internal fun QueueStage.label() = when (this) {
    QueueStage.WAITING -> R.string.queue_waiting
    QueueStage.CAPTURING -> R.string.home_recording
    QueueStage.PROCESSING, QueueStage.DRAINING -> R.string.home_notification_processing
    QueueStage.READY -> R.string.queue_ready
    QueueStage.FAILED -> R.string.queue_failed
    QueueStage.PAUSED -> R.string.queue_manually_paused
    QueueStage.PAUSING -> R.string.queue_pause_pending
    QueueStage.STOPPING -> R.string.queue_stop_pending
    QueueStage.STOPPED -> R.string.queue_manually_stopped
}

class TranscriptionQueueService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var latestStart = 0
    private var anchor: QueueRecord? = null
    private var observer: Job? = null
    private val notices by lazy { QueueNotifications(application as UtterlaneApp) }
    private val delivery get() = (application as UtterlaneApp).transcriptionQueue.terminalDelivery
    private fun foreground(candidate: QueueRecord?) = delivery.attach(candidate) { row ->
        val notification = row?.let { notices.notification(it) } ?: notices.serviceNotification()
        val id = row?.notificationId ?: 1031
        if (Build.VERSION.SDK_INT >= 29) startForeground(id, notification, when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }) else startForeground(id, notification)
        anchor = row
        (application as UtterlaneApp).transcriptionQueue.attached(this)
    }
    private fun detach() {
        val queue = (application as UtterlaneApp).transcriptionQueue
        queue.detached(this)
        delivery.detach({ keep -> stopForeground(if (keep) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE) },
            notices::remove, { row ->
                if (queue.isQuitting && row.stage == QueueStage.PAUSED) { notices.remove(row); false }
                else notices.publish(row)
            })
        anchor = null
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val app = application as UtterlaneApp
        val queue = app.transcriptionQueue
        try { if (observer == null) foreground(queue.rows.value.firstOrNull { it.stage.needsProcessingService }) } catch (e: RuntimeException) {
            android.util.Log.w("TranscriptionQueue", "Foreground service refused; queued inputs remain available", e)
            queue.serviceRefused()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        queue.admission.running()
        ImportRecoveryNotice(app).clearWaiting()
        if (observer == null) observer = scope.launch {
            queue.rows.collect { rows ->
                val next = rows.firstOrNull { it.id == anchor?.id && it.stage.needsProcessingService } ?: rows.firstOrNull { it.stage.needsProcessingService }
                if (next?.id != anchor?.id) try { detach(); foreground(next) }
                catch (e: RuntimeException) {
                    android.util.Log.w("TranscriptionQueue", "Foreground promotion failed", e)
                    queue.serviceRefused(); scope.cancel(); stopSelf()
                }
            }
        }
        queue.run(scope) {
            queue.admission.refused()
            queue.detached(this)
            detach()
            observer?.cancel(); observer = null
            stopSelfResult(latestStart)
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { detach(); scope.cancel(); stopSelf() }
    override fun onDestroy() {
        val queue = (application as UtterlaneApp).transcriptionQueue
        if (anchor != null) detach()
        if (queue.admission.status.value == QueueServiceAdmission.Status.RUNNING) queue.serviceRefused()
        queue.detached(this); scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
