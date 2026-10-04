package io.github.lrq3000.utterlane.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.DictionaryManager
import io.github.lrq3000.utterlane.asr.ModelManager
import io.github.lrq3000.utterlane.service.FloatingMicService
import io.github.lrq3000.utterlane.service.TextInjectionService
import io.github.lrq3000.utterlane.transcribe.TranscribeManager
import android.content.ActivityNotFoundException
import android.widget.Toast
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme
import io.github.lrq3000.utterlane.ui.BrandHeader
import io.github.lrq3000.utterlane.ui.BrandSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : LocalizedActivity() {

    private val refreshTrigger = mutableStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Just refresh UI - don't auto-start service
    }

    private var onModelFolderSelected: ((Uri) -> Unit)? = null

    private val modelFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { onModelFolderSelected?.invoke(it) }
    }

    private var onFolderSelected: ((String) -> Unit)? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable permission for the folder
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // Convert to a displayable path
            val path = getPathFromUri(it)
            if (path != null) {
                onFolderSelected?.invoke(path)
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri)

        // Handle primary storage paths
        if (docId.startsWith("primary:")) {
            val relativePath = docId.removePrefix("primary:")
            return "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
        }

        // For other storage (SD card, etc.), try to extract path
        if (docId.contains(":")) {
            val parts = docId.split(":")
            if (parts.size == 2) {
                return "/storage/${parts[0]}/${parts[1]}"
            }
        }

        // Fallback to URI string
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun openFolderPicker(callback: (String) -> Unit) {
        onFolderSelected = callback
        folderPickerLauncher.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settingsRepository = UtterlaneApp.instance.settingsRepository
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.THEME_SYSTEM
            )

            val isDarkTheme = when (themeMode) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> null
            }

            UtterlaneTheme(
                darkTheme = isDarkTheme ?: androidx.compose.foundation.isSystemInDarkTheme()
            ) {
                SettingsScreen(
                    refreshTrigger = refreshTrigger.value,
                    onRequestMicPermission = { requestMicPermission() },
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onRequestAudioFilesPermission = { requestAudioFilesPermission() },
                    hasAudioFilesPermission = { hasAudioFilesPermission() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenAppSettings = { openAppSettings() },
                    onStartService = {
                        requestNotificationPermissionIfNeeded()
                        startFloatingService()
                    },
                    onStopService = { stopFloatingService() },
                    onStartAudioMonitor = { requestNotificationPermissionIfNeeded() },
                    onRestartService = { restartFloatingService() },
                    onPickFolder = { callback -> openFolderPicker(callback) },
                    onPickModelFolder = { callback ->
                        onModelFolderSelected = callback
                        modelFolderPickerLauncher.launch(null)
                    },
                    isVoiceImeEnabled = { isVoiceImeEnabled() },
                    onOpenInputMethodSettings = { openInputMethodSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Trigger permission state refresh in Compose
        refreshTrigger.value++

        // Revalidate model files on disk
        UtterlaneApp.instance.modelManager.checkModelStatus()

        // Sync floating service state and restart services if needed (Android 14+ boot workaround)
        val app = UtterlaneApp.instance
        app.applicationScope.launch {
            val serviceEnabled = app.settingsRepository.serviceEnabled.first()
            val recognizerReady = app.recognizerManager.isInitialized()
            val hasMic = hasMicPermission()
            val hasOverlay = hasOverlayPermission()

            if (serviceEnabled) {
                if (!recognizerReady || !hasMic || !hasOverlay) {
                    // Conditions no longer met - disable
                    app.settingsRepository.setServiceEnabled(false)
                    stopFloatingService()
                } else {
                    // Conditions met - ensure service is running
                    startFloatingService()
                }
            }

            // Restart audio monitor if enabled (needed on Android 14+ where boot start is blocked)
            val audioMonitorEnabled = app.settingsRepository.audioMonitorEnabled.first()
            if (audioMonitorEnabled) {
                app.transcribeManager.setAudioMonitorEnabled(true)
            }

            // Dismiss the boot notification now that services are started
            app.serviceAlertNotification.dismiss()
        }
    }

    private fun requestMicPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestAudioFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasAudioFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed before Android 13
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun checkAndStartService() {
        if (hasMicPermission() && hasOverlayPermission()) {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingMicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingMicService::class.java).apply {
            action = FloatingMicService.ACTION_STOP
        }
        startService(intent)
    }

    private fun restartFloatingService() {
        // Stop without ACTION_STOP so preference isn't cleared, then start
        stopService(Intent(this, FloatingMicService::class.java))
        startFloatingService()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isVoiceImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        return enabledMethods.any { it.packageName == packageName }
    }

    private fun openInputMethodSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    refreshTrigger: Int,
    onRequestMicPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAudioFilesPermission: () -> Unit,
    hasAudioFilesPermission: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRestartService: () -> Unit,
    onStartAudioMonitor: () -> Unit,
    onPickFolder: ((String) -> Unit) -> Unit,
    onPickModelFolder: ((Uri) -> Unit) -> Unit,
    isVoiceImeEnabled: () -> Boolean,
    onOpenInputMethodSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = UtterlaneApp.instance.settingsRepository
    val modelManager = UtterlaneApp.instance.modelManager

    val serviceEnabled by settingsRepository.serviceEnabled.collectAsStateWithLifecycle(initialValue = false)
    val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = SettingsRepository.THEME_SYSTEM)
    val autoLoadModel by settingsRepository.autoLoadModel.collectAsStateWithLifecycle(initialValue = false)
    val downloadState by modelManager.downloadState.collectAsStateWithLifecycle()
    val selectedModel by modelManager.selected.collectAsStateWithLifecycle()
    // A confirmation belongs to the named model, not whichever model is selected later.
    var showModelDeleteDialog by remember(selectedModel.id) { mutableStateOf(false) }
    val recognizerManager = UtterlaneApp.instance.recognizerManager
    val isRecognizerReady by recognizerManager.isReady.collectAsStateWithLifecycle()
    val isRecognizerLoading by recognizerManager.isLoading.collectAsStateWithLifecycle()
    val recognizerFailure by recognizerManager.failure.collectAsStateWithLifecycle()
    val dictionaryManager = UtterlaneApp.instance.dictionaryManager
    val dictionaryEnabled by settingsRepository.dictionaryEnabled.collectAsStateWithLifecycle(initialValue = true)
    val replacementRules by dictionaryManager.rules.collectAsStateWithLifecycle()
    var showDictionaryDialog by remember { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    val audioMonitorEnabled by settingsRepository.audioMonitorEnabled.collectAsStateWithLifecycle(initialValue = false)
    val monitoredFolders by settingsRepository.monitoredFolders.collectAsStateWithLifecycle(initialValue = emptySet())
    val floatingButtonSize by settingsRepository.floatingButtonSize.collectAsStateWithLifecycle(initialValue = SettingsRepository.BUTTON_SIZE_MEDIUM)
    val transcribeManager = UtterlaneApp.instance.transcribeManager

    val hasMicPermission = remember { mutableStateOf(false) }
    val hasOverlayPermission = remember { mutableStateOf(false) }
    val hasAccessibilityEnabled = remember { mutableStateOf(false) }
    val hasAudioPermission = remember { mutableStateOf(false) }
    val hasVoiceImeEnabled = remember { mutableStateOf(false) }

    // Check permissions - refresh on every onResume via refreshTrigger
    LaunchedEffect(refreshTrigger) {
        hasMicPermission.value = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        hasOverlayPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        hasAccessibilityEnabled.value = TextInjectionService.isEnabled()

        hasAudioPermission.value = hasAudioFilesPermission()

        hasVoiceImeEnabled.value = isVoiceImeEnabled()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BrandHeader() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            // Speech Model (required for all voice input)
            SettingsSection(title = stringResource(R.string.section_speech_model)) {
                ModelSelector()
                ModelSettingItem(
                    isCustom = selectedModel.isCustom,
                    modelName = selectedModel.name,
                    modelBytes = selectedModel.downloadBytes,
                    importFiles = selectedModel.artifacts.joinToString(", ") { it.url.substringAfterLast('/').substringBefore('?') },
                    importUrl = selectedModel.artifacts.first().url.substringBefore("/resolve/"),
                    downloadState = downloadState,
                    isRecognizerReady = isRecognizerReady,
                    isRecognizerLoading = isRecognizerLoading,
                    canDeleteModel = modelManager.isModelReady() &&
                        downloadState !is ModelManager.DownloadState.Downloading &&
                        downloadState !is ModelManager.DownloadState.Copying,
                    onDeleteModel = { showModelDeleteDialog = true },
                    onDownload = {
                        scope.launch {
                            try { modelManager.downloadModel() }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() }
                        }
                    },
                    onLoadLocal = {
                        onPickModelFolder { uri ->
                            scope.launch {
                                try { modelManager.importFromFolder(uri) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() }
                            }
                        }
                    },
                    onLoadModel = {
                        modelManager.checkModelStatus()
                        if (modelManager.isModelReady()) {
                            scope.launch {
                                recognizerManager.initialize()
                            }
                        }
                    },
                    onUnloadModel = {
                        scope.launch {
                            try { recognizerManager.release() }
                            catch (e: Exception) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() }
                        }
                        // Stop floating service when model is unloaded
                        if (serviceEnabled) {
                            scope.launch {
                                settingsRepository.setServiceEnabled(false)
                            }
                            onStopService()
                        }
                    }
                )
                recognizerFailure?.let { message ->
                    Text(
                        text = stringResource(R.string.model_load_error_details, message),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                // Independent of ready/loading/download state: a failed or stuck
                // native load must never hide the one action that can recover it.
                TextButton(onClick = {
                    recognizerManager.forceUnload()
                    Toast.makeText(context, context.getString(R.string.model_reset_done), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.model_force_unload)) }
                if (downloadState is ModelManager.DownloadState.Downloading || downloadState is ModelManager.DownloadState.Copying) {
                    TextButton(onClick = { modelManager.cancelTransfer() }) { Text(stringResource(R.string.action_cancel)) }
                }
                if (showModelDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showModelDeleteDialog = false },
                        title = { Text(stringResource(R.string.model_delete_confirm_title)) },
                        text = { Text(stringResource(R.string.model_delete_confirm_message, selectedModel.name)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val confirmedModelId = selectedModel.id
                                    showModelDeleteDialog = false
                                    scope.launch {
                                        try { recognizerManager.deleteSelectedModel(confirmedModelId) }
                                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                        catch (e: Exception) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() }
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text(stringResource(R.string.model_delete_button)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showModelDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    )
                }

                val context = LocalContext.current
                Text(
                    text = if (selectedModel.id == "parakeet-v3") stringResource(R.string.model_attribution) else stringResource(R.string.model_moondream_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (selectedModel.id == "parakeet-v3") "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3" else "https://huggingface.co/moondream/parakeet-" + if (selectedModel.id.contains("ultra")) "ultra" else "redux")))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, context.getString(R.string.error_no_browser), Toast.LENGTH_SHORT).show()
                            }
                        }
                )

                SwitchSettingItem(
                    title = stringResource(R.string.model_auto_load_title),
                    subtitle = if (autoLoadModel) stringResource(R.string.model_auto_load_on) else stringResource(R.string.model_auto_load_off),
                    icon = Icons.Default.Speed,
                    checked = autoLoadModel,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setAutoLoadModel(enabled)
                        }
                    }
                )
            }

            // Microphone Permission (required for all voice input)
            SettingsSection(title = stringResource(R.string.section_microphone)) {
                PermissionItem(
                    title = stringResource(R.string.permission_mic_title),
                    subtitle = if (hasMicPermission.value) stringResource(R.string.permission_granted_manage) else stringResource(R.string.permission_required_voice),
                    icon = Icons.Default.Mic,
                    isGranted = hasMicPermission.value,
                    onClick = { if (hasMicPermission.value) onOpenAppSettings() else onRequestMicPermission() },
                    onRevokeClick = { onOpenAppSettings() }
                )
                io.github.lrq3000.utterlane.history.HistorySettings()
            }

            // Keyboard Integration Section
            SettingsSection(title = stringResource(R.string.section_keyboard)) {
                PermissionItem(
                    title = stringResource(R.string.keyboard_voice_ime),
                    subtitle = if (hasVoiceImeEnabled.value)
                        stringResource(R.string.keyboard_ime_enabled)
                    else stringResource(R.string.keyboard_ime_disabled),
                    icon = Icons.Default.Keyboard,
                    isGranted = hasVoiceImeEnabled.value,
                    onClick = { onOpenInputMethodSettings() },
                    onRevokeClick = { onOpenInputMethodSettings() }
                )
            }

            // Accessibility Service
            SettingsSection(title = stringResource(R.string.section_accessibility)) {
                PermissionItem(
                    title = stringResource(R.string.accessibility_text_injection),
                    subtitle = if (hasAccessibilityEnabled.value)
                        stringResource(R.string.accessibility_enabled)
                        else stringResource(R.string.accessibility_disabled),
                    icon = Icons.Default.Accessibility,
                    isGranted = hasAccessibilityEnabled.value,
                    onClick = {
                        if (hasAccessibilityEnabled.value) {
                            onOpenAccessibilitySettings()
                        } else {
                            showAccessibilityDisclosure = true
                        }
                    },
                    onRevokeClick = { onOpenAccessibilitySettings() }
                )
            }

            // Accessibility Disclosure Dialog
            if (showAccessibilityDisclosure) {
                AlertDialog(
                    onDismissRequest = { showAccessibilityDisclosure = false },
                    icon = { Icon(Icons.Default.Accessibility, contentDescription = null) },
                    title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(stringResource(R.string.accessibility_disclosure_body))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAccessibilityDisclosure = false
                            onOpenAccessibilitySettings()
                        }) {
                            Text(stringResource(R.string.accessibility_disclosure_agree))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAccessibilityDisclosure = false }) {
                            Text(stringResource(R.string.accessibility_disclosure_decline))
                        }
                    }
                )
            }

            // Optional Floating Mic Button
            SettingsSection(title = stringResource(R.string.section_floating_mic)) {
                SwitchSettingItem(
                    title = stringResource(R.string.floating_enable),
                    subtitle = when {
                        !hasMicPermission.value -> stringResource(R.string.floating_grant_mic_first)
                        !hasOverlayPermission.value -> stringResource(R.string.floating_grant_overlay_first)
                        !isRecognizerReady -> stringResource(R.string.floating_load_model_first)
                        serviceEnabled -> stringResource(R.string.floating_shows_red)
                        else -> stringResource(R.string.floating_additional)
                    },
                    icon = Icons.Default.RadioButtonChecked,
                    checked = serviceEnabled,
                    enabled = hasMicPermission.value && hasOverlayPermission.value && isRecognizerReady,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setServiceEnabled(enabled)
                            if (enabled) onStartService() else onStopService()
                        }
                    }
                )

                PermissionItem(
                    title = stringResource(R.string.setting_overlay),
                    subtitle = if (hasOverlayPermission.value) stringResource(R.string.permission_granted_manage) else stringResource(R.string.permission_required_overlay),
                    icon = Icons.Default.Layers,
                    isGranted = hasOverlayPermission.value,
                    onClick = { onRequestOverlayPermission() },
                    onRevokeClick = { onRequestOverlayPermission() }
                )

                ButtonSizeSettingItem(
                    selectedSize = floatingButtonSize,
                    onSizeSelected = { size ->
                        scope.launch {
                            settingsRepository.setFloatingButtonSize(size)
                            // Restart service to apply new size (without clearing preference)
                            if (serviceEnabled) {
                                onRestartService()
                            }
                        }
                    }
                )
            }

            // Transcription Section
            SettingsSection(title = stringResource(R.string.section_transcription)) {
                PermissionItem(
                    title = stringResource(R.string.transcription_audio_access),
                    subtitle = if (hasAudioPermission.value) stringResource(R.string.permission_granted_manage) else stringResource(R.string.permission_required_audio),
                    icon = Icons.Default.AudioFile,
                    isGranted = hasAudioPermission.value,
                    onClick = { if (hasAudioPermission.value) onOpenAppSettings() else onRequestAudioFilesPermission() },
                    onRevokeClick = { onOpenAppSettings() }
                )

                SwitchSettingItem(
                    title = stringResource(R.string.transcription_monitor_folders),
                    subtitle = when {
                        !hasAudioPermission.value -> stringResource(R.string.transcription_grant_audio_first)
                        audioMonitorEnabled -> {
                            val count = monitoredFolders.size
                            if (count > 0) stringResource(R.string.transcription_watching_folders, count)
                            else stringResource(R.string.transcription_watching_downloads)
                        }
                        else -> stringResource(R.string.transcription_notify_downloaded)
                    },
                    icon = Icons.Default.FolderOpen,
                    checked = audioMonitorEnabled,
                    enabled = hasAudioPermission.value,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            if (enabled) onStartAudioMonitor()
                            settingsRepository.setAudioMonitorEnabled(enabled)
                            transcribeManager.setAudioMonitorEnabled(enabled)
                        }
                    }
                )

                // Watched folders list
                if (audioMonitorEnabled || monitoredFolders.isNotEmpty()) {
                    val displayFolders = monitoredFolders.ifEmpty {
                        setOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                    }

                    displayFolders.forEach { folder ->
                        val folderName = folder.substringAfterLast("/")
                        val isDefault = monitoredFolders.isEmpty()
                        ListItem(
                            headlineContent = { Text(folderName) },
                            supportingContent = { Text(folder) },
                            leadingContent = {
                                Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            trailingContent = {
                                if (!isDefault) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            val newFolders = monitoredFolders - folder
                                            settingsRepository.setMonitoredFolders(newFolders)
                                            // Restart service to stop watching removed folder
                                            if (audioMonitorEnabled) {
                                                transcribeManager.setAudioMonitorEnabled(false)
                                                transcribeManager.setAudioMonitorEnabled(true)
                                            }
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.action_remove),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else {
                                    Text(
                                        stringResource(R.string.transcription_default_folder),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }

                    // Add folder button
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.transcription_add_folder)) },
                        supportingContent = { Text(stringResource(R.string.transcription_add_folder_desc)) },
                        leadingContent = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            onPickFolder { path ->
                                scope.launch {
                                    // If adding first custom folder, include default Downloads
                                    val baseFolders = monitoredFolders.ifEmpty {
                                        setOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                                    }
                                    val newFolders = baseFolders + path
                                    settingsRepository.setMonitoredFolders(newFolders)
                                    // Restart service to pick up new folder
                                    if (audioMonitorEnabled) {
                                        transcribeManager.setAudioMonitorEnabled(false)
                                        transcribeManager.setAudioMonitorEnabled(true)
                                    }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text(stringResource(R.string.transcription_share_open)) },
                    supportingContent = { Text(stringResource(R.string.transcription_share_open_desc)) },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null) }
                )
            }

            // Word Corrections Section
            SettingsSection(title = stringResource(R.string.section_word_corrections)) {
                SwitchSettingItem(
                    title = stringResource(R.string.corrections_enable),
                    subtitle = if (dictionaryEnabled) stringResource(R.string.corrections_enabled_desc) else stringResource(R.string.corrections_disabled_desc),
                    icon = Icons.Default.Spellcheck,
                    checked = dictionaryEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsRepository.setDictionaryEnabled(enabled)
                        }
                    }
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.corrections_manage)) },
                    supportingContent = { Text(stringResource(R.string.corrections_count, replacementRules.size)) },
                    leadingContent = { Icon(Icons.Default.EditNote, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.corrections_manage))
                    },
                    modifier = Modifier.clickable { showDictionaryDialog = true }
                )
            }

            // Dictionary Dialog
            if (showDictionaryDialog) {
                DictionaryDialog(
                    rules = replacementRules,
                    onDismiss = { showDictionaryDialog = false },
                    onAddRule = { from, to ->
                        scope.launch {
                            dictionaryManager.addRule(from, to)
                        }
                    },
                    onRemoveRule = { from ->
                        scope.launch {
                            dictionaryManager.removeRule(from)
                        }
                    }
                )
            }

            DiarizationSettings(onPickModelFolder)

            // Appearance Section
            SettingsSection(title = stringResource(R.string.section_appearance)) {
                AppLanguageSetting()
                ThemeSettingItem(
                    selectedTheme = themeMode,
                    onThemeSelected = { theme ->
                        scope.launch {
                            settingsRepository.setThemeMode(theme)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    BrandSection(title, content)
}

@Composable
fun SwitchSettingItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
        supportingContent = { Text(subtitle, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) },
        leadingContent = { Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    )
}

@Composable
fun PermissionItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isGranted: Boolean,
    onClick: () -> Unit,
    onRevokeClick: (() -> Unit)? = null
) {
    val actualClick = if (isGranted && onRevokeClick != null) onRevokeClick else onClick
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (isGranted) {
                Icon(Icons.Default.Check, stringResource(R.string.permission_granted_manage), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Default.ChevronRight, stringResource(R.string.action_grant_permission), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable(onClick = actualClick)
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ModelSettingItem(
    modelName: String,
    modelBytes: Long,
    importFiles: String,
    importUrl: String,
    downloadState: ModelManager.DownloadState,
    isRecognizerReady: Boolean,
    isRecognizerLoading: Boolean,
    canDeleteModel: Boolean,
    onDeleteModel: () -> Unit,
    onDownload: () -> Unit,
    onLoadLocal: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    isCustom: Boolean = false
) {
    var showImportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column {
        ListItem(
            headlineContent = { Text(modelName) },
            supportingContent = {
                when {
                    downloadState is ModelManager.DownloadState.NotStarted -> Text(stringResource(R.string.model_not_downloaded) + "\n${modelBytes / 1000000} MB")
                    downloadState is ModelManager.DownloadState.Downloading -> Text(stringResource(R.string.model_downloading, downloadState.progress))
                    downloadState is ModelManager.DownloadState.Copying -> Text(stringResource(R.string.model_copying, downloadState.progress))
                    downloadState is ModelManager.DownloadState.Extracting -> Text(stringResource(R.string.model_extracting))
                    downloadState is ModelManager.DownloadState.Error -> Text(
                        when (downloadState.type) {
                            ModelManager.ErrorType.NETWORK -> stringResource(R.string.model_error_network)
                            ModelManager.ErrorType.CHECKSUM_MISMATCH -> stringResource(R.string.model_error_checksum)
                            ModelManager.ErrorType.MISSING_FILE -> stringResource(R.string.model_error_missing_file, downloadState.details ?: "")
                            ModelManager.ErrorType.FOLDER_ACCESS -> stringResource(R.string.model_error_folder_access)
                            ModelManager.ErrorType.STORAGE -> stringResource(R.string.model_error_storage)
                            ModelManager.ErrorType.UNKNOWN -> stringResource(R.string.model_error, downloadState.details ?: "")
                        }
                    )
                    isRecognizerLoading -> Text(stringResource(R.string.model_loading))
                    isRecognizerReady -> Text(stringResource(R.string.model_loaded))
                    downloadState is ModelManager.DownloadState.Ready -> Text(stringResource(R.string.model_downloaded))
                }
            },
            leadingContent = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        )
        // Long model names and translated labels need the full row width. Actions
        // wrap below the details instead of squeezing them beside a button stack.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when {
                downloadState is ModelManager.DownloadState.NotStarted ||
                downloadState is ModelManager.DownloadState.Error -> {
                    if (isCustom) Text(stringResource(R.string.model_custom_reimport))
                    else Button(onClick = onDownload, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.action_download))
                    }
                    if (!isCustom) TextButton(onClick = { showImportDialog = true }) {
                        Text(stringResource(R.string.action_load_local))
                    }
                }
                downloadState is ModelManager.DownloadState.Downloading ||
                downloadState is ModelManager.DownloadState.Copying -> {
                    val progress = when (downloadState) {
                        is ModelManager.DownloadState.Downloading -> downloadState.progress
                        is ModelManager.DownloadState.Copying -> downloadState.progress
                        else -> 0
                    }
                    CircularProgressIndicator(progress = { progress / 100f }, modifier = Modifier.size(24.dp))
                }
                downloadState is ModelManager.DownloadState.Extracting || isRecognizerLoading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                isRecognizerReady -> {
                    TextButton(onClick = onUnloadModel) { Text(stringResource(R.string.model_unload)) }
                }
                downloadState is ModelManager.DownloadState.Ready -> {
                    Button(onClick = onLoadModel, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.model_load))
                    }
                }
            }
            if (canDeleteModel) {
                // Same filled, rounded button as Download/Load;
                // only the destructive-action colors differ.
                Button(
                    onClick = onDeleteModel,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text(stringResource(R.string.model_delete_button)) }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.model_import_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.model_import_catalog, importFiles))
                    Spacer(modifier = Modifier.size(12.dp))
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(importUrl)))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, context.getString(R.string.error_no_browser), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.model_import_open_link))
                    }
                }
            },
            confirmButton = {
                Button(shape = MaterialTheme.shapes.small, onClick = {
                    showImportDialog = false
                    onLoadLocal()
                }) {
                    Text(stringResource(R.string.action_select_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun ThemeSettingItem(
    selectedTheme: String,
    onThemeSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val themes = listOf(
        SettingsRepository.THEME_SYSTEM to stringResource(R.string.setting_theme_system),
        SettingsRepository.THEME_LIGHT to stringResource(R.string.setting_theme_light),
        SettingsRepository.THEME_DARK to stringResource(R.string.setting_theme_dark)
    )
    val selectedThemeName = themes.find { it.first == selectedTheme }?.second ?: stringResource(R.string.setting_theme_system)

    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_theme)) },
        supportingContent = { Text(selectedThemeName) },
        leadingContent = { Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { expanded = true }
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        themes.forEach { (code, name) ->
            DropdownMenuItem(
                text = { Text(name) },
                onClick = {
                    onThemeSelected(code)
                    expanded = false
                },
                leadingIcon = if (code == selectedTheme) {
                    { Icon(Icons.Default.Check, null) }
                } else null
            )
        }
    }
}

@Composable
fun ButtonSizeSettingItem(
    selectedSize: String,
    onSizeSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sizes = listOf(
        SettingsRepository.BUTTON_SIZE_SMALL to stringResource(R.string.floating_size_small),
        SettingsRepository.BUTTON_SIZE_MEDIUM to stringResource(R.string.floating_size_medium),
        SettingsRepository.BUTTON_SIZE_LARGE to stringResource(R.string.floating_size_large)
    )
    val selectedSizeName = sizes.find { it.first == selectedSize }?.second ?: stringResource(R.string.floating_size_medium)

    ListItem(
        headlineContent = { Text(stringResource(R.string.floating_button_size)) },
        supportingContent = { Text(selectedSizeName) },
        leadingContent = { Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { expanded = true }
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        sizes.forEach { (code, name) ->
            DropdownMenuItem(
                text = { Text(name) },
                onClick = {
                    onSizeSelected(code)
                    expanded = false
                },
                leadingIcon = if (code == selectedSize) {
                    { Icon(Icons.Default.Check, null) }
                } else null
            )
        }
    }
}

@Composable
fun DictionaryDialog(
    rules: List<DictionaryManager.ReplacementRule>,
    onDismiss: () -> Unit,
    onAddRule: (String, String) -> Unit,
    onRemoveRule: (String) -> Unit
) {
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        unfocusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.corrections_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                // Add new rule form
                Text(
                    stringResource(R.string.corrections_add_new),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        label = { Text(stringResource(R.string.corrections_from)) },
                        colors = fieldColors,
                        shape = MaterialTheme.shapes.small,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text(stringResource(R.string.corrections_to)) },
                        colors = fieldColors,
                        shape = MaterialTheme.shapes.small,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (fromText.isNotBlank() && toText.isNotBlank()) {
                                onAddRule(fromText.trim(), toText.trim())
                                fromText = ""
                                toText = ""
                            }
                        },
                        enabled = fromText.isNotBlank() && toText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.action_add))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Existing rules list
                Text(
                    stringResource(R.string.corrections_current_rules, rules.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (rules.isEmpty()) {
                    Text(
                        stringResource(R.string.corrections_no_rules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        rules.forEach { rule ->
                            ListItem(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(rule.from)
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(horizontal = 8.dp)
                                                .size(16.dp)
                                        )
                                        Text(rule.to)
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { onRemoveRule(rule.from) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.action_remove),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.corrections_done))
            }
        }
    )
}
