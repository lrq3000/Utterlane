package io.github.lrq3000.utterlane.archive

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.home.HomeActivity

/** Lives in the parent so notification control survives a hung provider/codec. */
class ArchiveSupervisorService : Service() {
    private val app get() = application as UtterlaneApp
    private var encoding = false
    internal fun enableMediaProcessing() { if (!encoding) { encoding = true; foreground() } }
    private fun foreground() {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or when {
            !encoding -> 0
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0 // Pre-34 dataSync explicitly covers local file processing.
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(app.archival.state.value), type)
        else startForeground(ID, notification(app.archival.state.value))
    }
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.archive_title), NotificationManager.IMPORTANCE_LOW))
            encoding = encoding || intent?.getBooleanExtra("encoding", false) == true
            foreground()
            app.archival.attached(this)
            if (intent?.action == STOP) app.archival.forceStopAll()
        } catch (e: Exception) { app.archival.startFailed(e); finish() }
        return START_NOT_STICKY
    }
    internal fun update(state: ArchivalStatus) {
        getSystemService(NotificationManager::class.java).notify(ID, notification(state))
    }
    private fun notification(state: ArchivalStatus): Notification {
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, ID, Intent(this, ArchiveSupervisorService::class.java).setAction(STOP), immutable)
        val open = PendingIntent.getActivity(this, ID, Intent(this, HomeActivity::class.java), immutable)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.archive_title))
            .setContentText(getString(R.string.archive_notification, state.queued + state.running, state.producers))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.archive_force_stop), stop).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.archival.pauseAndStop(getString(R.string.archive_timeout))
    }
    internal fun finish() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { app.archival.detached(this); super.onDestroy() }
    companion object {
        private const val CHANNEL = "managed_archival"
        private const val ID = 1015
        private const val STOP = "io.github.lrq3000.utterlane.archive.STOP_ALL"
    }
}
