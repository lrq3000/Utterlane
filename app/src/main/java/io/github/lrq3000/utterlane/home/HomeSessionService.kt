package io.github.lrq3000.utterlane.home

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** No sticky restart: Android must never reopen the microphone without a new user action. */
class HomeSessionService : Service() {
    companion object {
        internal const val TOKEN = "home_session_token"
        internal const val MICROPHONE = "home_microphone"
        private const val STOP = "io.github.lrq3000.utterlane.home.STOP"
        private const val NOTIFICATION = 1010
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as UtterlaneApp).homeController
    private var token: String? = null
    private var observer: Job? = null
    private var expectedStop = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(TOKEN)
        if (intent?.action == STOP) {
            requested?.let(controller::stopCapture)
            return START_NOT_STICKY
        }
        if (!controller.ownsService(requested)) {
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        token = requested
        expectedStop = false
        val microphone = intent!!.getBooleanExtra(MICROPHONE, false)
        try {
            val notification = notification(requested!!, microphone)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification,
                if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            else startForeground(NOTIFICATION, notification)
            controller.serviceStarted(requested)
            observer?.cancel()
            observer = scope.launch {
                controller.state.collect { state ->
                    if (!state.busy) {
                        expectedStop = true
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
        } catch (error: Exception) {
            controller.serviceFailed(requested!!, error)
            expectedStop = true
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun notification(token: String, microphone: Boolean): android.app.Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!
            .putExtra(io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true)
        val back = PendingIntent.getActivity(this, NOTIFICATION, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, UtterlaneApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_utterlane_notification)
            .setContentTitle(getString(R.string.home_notification_title))
            .setContentText(getString(if (microphone) R.string.home_notification_recording else R.string.home_notification_processing))
            .setContentIntent(back).setOngoing(true).setOnlyAlertOnce(true)
            .apply { if (microphone) addAction(0, getString(R.string.home_stop), PendingIntent.getService(
                this@HomeSessionService, NOTIFICATION,
                Intent(this@HomeSessionService, HomeSessionService::class.java).setAction(STOP).putExtra(TOKEN, token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            .build()
    }
    override fun onDestroy() {
        token?.let { controller.serviceDestroyed(it, expectedStop) }
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
