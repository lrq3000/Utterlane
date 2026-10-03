package com.translander

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.translander.asr.DictionaryManager
import com.translander.asr.ModelManager
import com.translander.asr.RecognizerManager
import com.translander.notification.ServiceAlertNotification
import com.translander.settings.SettingsRepository
import com.translander.transcribe.TranscribeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.translander.history.RecordingHistory
import com.translander.history.HistoryCleanupService
import java.io.File
import com.translander.asr.CacheArtifacts
import com.translander.asr.MicrophoneSessionFactory

class TranslanderApp : Application() {

    var microphoneSessions = MicrophoneSessionFactory()
        internal set

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var modelManager: ModelManager
        private set

    lateinit var recognizerManager: RecognizerManager
        private set

    lateinit var dictionaryManager: DictionaryManager
        private set

    lateinit var transcribeManager: TranscribeManager
        private set

    lateinit var recordingHistory: RecordingHistory
        private set

    val serviceAlertNotification: ServiceAlertNotification by lazy {
        ServiceAlertNotification(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        dictionaryManager = DictionaryManager(this)
        modelManager = ModelManager(this)
        recognizerManager = RecognizerManager(this, modelManager)
        transcribeManager = TranscribeManager(this)
        recordingHistory = RecordingHistory(File(filesDir, "microphone-history"))
        createNotificationChannel()

        // Policy changes and recovery/pruning never scan storage on the UI thread.
        applicationScope.launch(Dispatchers.IO) {
            settingsRepository.historyRetention.collect { retention ->
                try { recordingHistory.prune(retention) }
                catch (e: Exception) { Log.e(TAG, "History cleanup failed", e) }
            }
        }
        HistoryCleanupService.schedule(this)
        applicationScope.launch(Dispatchers.IO) {
            // Shared export grants need the file after a dialog closes. Expire old
            // temporary text on startup, rather than deleting it during sharing.
            cleanupCacheArtifacts()
        }

        // Auto-load model on startup if setting is enabled
        applicationScope.launch(Dispatchers.IO) {
            val autoLoad = settingsRepository.autoLoadModel.first()
            if (autoLoad && modelManager.isModelReady()) {
                Log.i(TAG, "Auto-loading speech model")
                recognizerManager.initialize()
            }
        }

        // Auto-start audio monitor if enabled
        try {
            transcribeManager.startEnabledTriggers()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start enabled triggers", e)
        }
    }

    fun cleanupCacheArtifacts() {
        CacheArtifacts.prune(File(cacheDir, "transcripts"), 7 * 86400000L, includeDirectories = false)
        CacheArtifacts.prune(File(cacheDir, "transcripts/exports"), 86400000L)
        CacheArtifacts.prune(File(cacheDir, "history-exports"), 86400000L)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Voice Transcribe Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when voice transcription service is active"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            val alertChannel = NotificationChannel(
                SERVICE_ALERT_CHANNEL_ID,
                getString(R.string.service_alert_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_alert_channel_description)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        private const val TAG = "TranslanderApp"
        const val NOTIFICATION_CHANNEL_ID = "voice_transcribe_service"
        const val SERVICE_ALERT_CHANNEL_ID = "service_alert"
        const val NOTIFICATION_ID = 1001
        const val SERVICE_ALERT_NOTIFICATION_ID = 1002

        lateinit var instance: TranslanderApp
            private set
    }
}
