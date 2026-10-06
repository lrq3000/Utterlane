package io.github.lrq3000.utterlane.onboarding

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.DiarizationModel
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.asr.ModelDefinition
import io.github.lrq3000.utterlane.asr.ModelManager
import io.github.lrq3000.utterlane.service.FloatingMicService
import io.github.lrq3000.utterlane.service.TextInjectionService
import io.github.lrq3000.utterlane.transcribe.AudioMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Only this adapter translates between the standalone guide and app internals. */
class AppOnboardingServices(private val app: UtterlaneApp) : OnboardingServices {
    private val settings = app.settingsRepository
    private val speech = app.modelManager
    private val speakers = app.diarizationModels
    private val ready = MutableStateFlow(false)
    private val permissions = MutableStateFlow(OnboardingPermissions())
    private val definitions = ModelCatalog.models.associateBy { it.id }
    private val featured = listOfNotNull(
        // A future app default need not be Ultra; named cards use stable catalog
        // identities, while an unfeatured current model gets its own generic card.
        definitions["parakeet-ultra-q8_0"]?.let { option(it, "Parakeet Ultra Q8", ModelExplanation.ULTRA_Q8, ModelTier.FULL) },
        definitions["parakeet-ultra-q4_k"]?.let { option(it, "Parakeet Ultra Q4", ModelExplanation.ULTRA_Q4, ModelTier.BALANCED) },
        definitions[ModelCatalog.REDUX_TERNARY.id]?.let { option(it, "Parakeet Redux", ModelExplanation.REDUX, ModelTier.COMPACT) },
        definitions[ModelCatalog.PARAKEET_V3.id]?.let { option(it, "Parakeet v3", ModelExplanation.ORIGINAL) }
    )
    private val featuredIds = featured.map { it.id }.toSet()
    private val ram = ActivityManager.MemoryInfo().also {
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem

    private fun option(model: ModelDefinition, name: String = model.name,
        explanation: ModelExplanation = ModelExplanation.CURRENT, tier: ModelTier? = null) =
        OnboardingModel(model.id, name, model.downloadBytes, explanation, tier)

    override fun observe(scope: CoroutineScope): StateFlow<OnboardingDeviceState> {
        val preferences = combine(settings.themeMode, settings.serviceEnabled,
            settings.audioMonitorEnabled, settings.monitoredFolders, settings.diarizationEnabled
        ) { theme, floating, monitor, folders, diarization ->
            OnboardingPreferences(theme, floating, monitor,
                folders.ifEmpty { if (monitor) AudioMonitorService.getDefaultMonitoredPaths() else emptySet() }, diarization)
        }.combine(settings.speakerCount) { prefs, count -> prefs.copy(speakerCount = count) }
            .combine(settings.hasSavedSettings) { prefs, configured -> prefs.copy(previouslyConfigured = configured) }

        return combine(preferences, speech.selected, speech.downloadState, speakers.downloadState, permissions) {
            prefs, selected, download, speakerDownload, granted ->
            OnboardingDeviceState(models = if (selected.id in featuredIds) featured else featured + option(selected),
                selectedModelId = selected.id, ramBytes = ram, speakerBytes = DiarizationModel.definition.downloadBytes,
                preferences = prefs, permissions = granted, speechTransfer = transferState(download),
                speakerTransfer = transferState(speakerDownload))
        }.combine(ready) { state, initialized -> state.copy(initialized = initialized) }
            .stateIn(scope, SharingStarted.Eagerly, OnboardingDeviceState())
    }

    override suspend fun initialize() {
        speech.initializeSelection()
        speakers.initializeSelection()
        refreshPermissions()
        ready.value = true
    }

    override fun refreshPermissions() {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
        permissions.value = OnboardingPermissions(
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            audioFiles = granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE),
            notifications = NotificationManagerCompat.from(app).areNotificationsEnabled(),
            overlay = Settings.canDrawOverlays(app),
            accessibility = TextInjectionService.isEnabled(),
            keyboard = app.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == app.packageName },
            floatingRunning = floatingServiceRunning()
        )
    }

    @Suppress("DEPRECATION")
    private fun floatingServiceRunning(): Boolean =
        // Since API 26 this API reports only the calling app's services, which
        // is exactly the scope needed here. A saved preference alone is not live
        // evidence after Android has revoked permission or stopped the service.
        app.getSystemService(ActivityManager::class.java).getRunningServices(100).any {
            it.service.className == FloatingMicService::class.java.name && it.started && it.pid != 0
        }

    private fun transferState(state: ModelManager.DownloadState): OnboardingTransfer = when (state) {
        ModelManager.DownloadState.NotStarted -> OnboardingTransfer()
        is ModelManager.DownloadState.Downloading -> OnboardingTransfer(TransferPhase.DOWNLOADING, state.progress)
        is ModelManager.DownloadState.Copying -> OnboardingTransfer(TransferPhase.IMPORTING, state.progress)
        ModelManager.DownloadState.Extracting -> OnboardingTransfer(TransferPhase.VERIFYING)
        ModelManager.DownloadState.Ready -> OnboardingTransfer(TransferPhase.READY, 100)
        is ModelManager.DownloadState.Error -> OnboardingTransfer(TransferPhase.ERROR, error = state.details ?: app.getString(R.string.model_error_checksum))
    }

    override suspend fun selectModel(id: String) {
        val model = definitions[id] ?: speech.customModels.value.firstOrNull { it.id == id }
            ?: error(app.getString(R.string.model_not_downloaded))
        if (speech.selected.value.id != id) app.recognizerManager.selectModel(model)
    }

    override suspend fun transfer(speakers: Boolean, folder: Uri?) {
        val manager = if (speakers) this.speakers else speech
        if (folder == null) manager.downloadModel() else manager.importFromFolder(folder)
        val result = manager.downloadState.value
        if (result is ModelManager.DownloadState.Error) error(result.details ?: app.getString(R.string.model_error_checksum))
    }
    override suspend fun verify(speakers: Boolean) = (if (speakers) this.speakers else speech).ensureVerified()
    override fun cancelTransfer(speakers: Boolean) = (if (speakers) this.speakers else speech).cancelTransfer()
    override suspend fun setTheme(mode: String) {
        require(mode == "system" || mode == "light" || mode == "dark")
        settings.setThemeMode(mode)
    }
    override suspend fun setSpeakers(enabled: Boolean) {
        if (enabled && settings.speakerCount.first() != 1) {
            check(verify(true)) { app.getString(R.string.diarization_install_first) }
        }
        // Fresh installs already default to automatic count. Re-enabling labels
        // during a replay must retain a count explicitly chosen in Settings.
        settings.setDiarizationEnabled(enabled)
    }

    override suspend fun setMonitor(enabled: Boolean) {
        if (enabled) {
            val folders = settings.monitoredFolders.first().ifEmpty {
                if (settings.audioMonitorEnabled.first()) AudioMonitorService.getDefaultMonitoredPaths() else emptySet()
            }
            validateMonitoring(folders)
            // Deliver onStartCommand even to an existing service, so newly chosen
            // folders are observed without a stop/start isRunning race.
            app.startForegroundService(Intent(app, AudioMonitorService::class.java))
        } else app.transcribeManager.setAudioMonitorEnabled(false)
        settings.setAudioMonitorEnabled(enabled)
    }

    private suspend fun validateMonitoring(folders: Set<String>) {
        refreshPermissions()
        val readable = withContext(Dispatchers.IO) { folders.isNotEmpty() && folders.all { File(it).let { f -> f.isDirectory && f.canRead() } } }
        check(permissions.value.canMonitor(verify(false), readable)) { app.getString(R.string.onboarding_monitor_requirements) }
    }

    override suspend fun addFolder(path: String) = changeFolders { it + path }
    override suspend fun removeFolder(path: String) = changeFolders { it - path }

    private suspend fun changeFolders(change: (Set<String>) -> Set<String>) {
        val enabled = settings.audioMonitorEnabled.first()
        val current = settings.monitoredFolders.first().ifEmpty {
            if (enabled) AudioMonitorService.getDefaultMonitoredPaths() else emptySet()
        }
        val updated = change(current)
        if (updated == current) return
        if (updated.isEmpty()) {
            // Empty must not reactivate the legacy Downloads fallback.
            if (enabled) setMonitor(false)
            settings.setMonitoredFolders(updated)
            return
        }
        // Additions must validate before changing a working configuration.
        // Pure removals are always recoverable: otherwise two unavailable SD
        // card folders would prevent either one being removed individually.
        if (enabled && updated.any { it !in current }) validateMonitoring(updated)
        withContext(NonCancellable) {
            settings.setMonitoredFolders(updated)
            // Editing stored paths refreshes an existing watcher; it does not
            // start an inactive foreground service behind a saved preference.
            // The explicit Enable/Continue action owns starting that service.
            if (enabled && app.transcribeManager.isAudioMonitorActive()) {
                try { app.startForegroundService(Intent(app, AudioMonitorService::class.java)) }
                catch (error: Exception) { settings.setMonitoredFolders(current); throw error }
            }
        }
    }

    override suspend fun enableFloating() {
        refreshPermissions()
        check(permissions.value.canFloat(verify(false))) { app.getString(R.string.onboarding_floating_body) }
        app.startForegroundService(Intent(app, FloatingMicService::class.java))
        settings.setServiceEnabled(true)
        refreshPermissions()
    }

    override fun voiceSession(scope: CoroutineScope, callbacks: OnboardingVoiceTrial.Callbacks): OnboardingTrialSession {
        val session = app.microphoneSessions.create(app, scope,
            onText = { _, store -> callbacks.onText(store.preview()) },
            onComplete = { store, failure ->
                store?.let { callbacks.onText(it.preview()) }
                callbacks.onComplete(failure?.message)
                store?.let { app.applicationScope.launch(Dispatchers.IO) { it.dispose() } }
            },
            onCaptureEnded = callbacks.onCaptureEnded, onWarning = callbacks.onWarning,
            onReady = callbacks.onReady, onSessionClosed = callbacks.onClosed)
        return object : OnboardingTrialSession {
            override fun start() = session.start()
            override fun stop() = session.stop()
            override fun cancel() = session.cancel()
        }
    }
}
