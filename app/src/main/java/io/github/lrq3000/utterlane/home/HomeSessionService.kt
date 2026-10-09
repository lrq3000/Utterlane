package io.github.lrq3000.utterlane.home

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.Uri
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
    private var stopAction: PendingIntent? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(TOKEN)
        if (intent?.action == STOP) {
            if (controller.ownsService(requested)) controller.stopCapture(requested!!)
            else if (token == null) stopSelf(startId)
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
                    if (controller.ownsService(requested) && !state.busy) {
                        HomeServiceIdleStop.recheck(
                            isIdle = { controller.ownsService(requested) && !controller.state.value.busy },
                            onIdle = {
                                expectedStop = true
                                // A retained detail model can start another retry before
                                // onDestroy. Stop is no longer attached ownership for it.
                                controller.serviceStopping(requested)
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                // Notification Stop advances startId, so using the
                                // capture's original ID here would leak an idle service.
                                stopSelf()
                            })
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
        val launch = HomeActivity.intent(this, HomeDestination.RECORD, internal = true)
        val back = PendingIntent.getActivity(this, NOTIFICATION, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        stopAction?.cancel()
        stopAction = if (microphone) PendingIntent.getService(this, NOTIFICATION,
            Intent(this, HomeSessionService::class.java).setAction(STOP).putExtra(TOKEN, token)
                // PendingIntent identity excludes extras. A token in data prevents
                // an old notification action being retargeted to a later capture.
                .setData(Uri.parse("utterlane://home/stop/$token")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null
        return NotificationCompat.Builder(this, UtterlaneApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_utterlane_notification)
            .setContentTitle(getString(R.string.home_notification_title))
            .setContentText(getString(if (microphone) R.string.home_notification_recording else R.string.home_notification_processing))
            .setContentIntent(back).setOngoing(true).setOnlyAlertOnce(true)
            .apply { stopAction?.let { addAction(0, getString(R.string.home_stop), it) } }
            .build()
    }
    override fun onDestroy() {
        stopAction?.cancel()
        token?.let { controller.serviceDestroyed(it, expectedStop) }
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
