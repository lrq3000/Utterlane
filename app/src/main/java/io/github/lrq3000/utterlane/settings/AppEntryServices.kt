package io.github.lrq3000.utterlane.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.service.FloatingMicService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Both real app entry surfaces restore enabled services after Android's boot restrictions. */
object AppEntryServices {
    fun restore(context: Context) {
        val app = context.applicationContext as UtterlaneApp
        app.modelManager.checkModelStatus()
        app.diarizationModels.checkModelStatus()
        app.applicationScope.launch {
            try {
                if (app.settingsRepository.serviceEnabled.first()) {
                    // Capture is independent of recognition/model availability.
                    // Keep the shared Home/Settings restoration path permission-only.
                    val available = Settings.canDrawOverlays(app) &&
                        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (available) ContextCompat.startForegroundService(app, Intent(app, FloatingMicService::class.java))
                    else {
                        app.settingsRepository.setServiceEnabled(false)
                        app.startService(Intent(app, FloatingMicService::class.java).setAction(FloatingMicService.ACTION_STOP))
                    }
                }
                if (app.settingsRepository.audioMonitorEnabled.first()) app.transcribeManager.setAudioMonitorEnabled(true)
                // Only dismiss the boot reminder after enabled services were restored.
                app.serviceAlertNotification.dismiss()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { Log.e("AppEntryServices", "Could not restore enabled services", error) }
        }
    }
}
