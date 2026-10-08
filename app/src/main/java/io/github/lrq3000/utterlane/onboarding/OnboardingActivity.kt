package io.github.lrq3000.utterlane.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.LocalizedActivity
import io.github.lrq3000.utterlane.settings.SettingsActivity
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OnboardingActivity : LocalizedActivity() {
    companion object { const val EXTRA_REPLAY = "replay_onboarding" }
    private val model by viewModels<OnboardingViewModel> { OnboardingViewModel.Factory(application as UtterlaneApp) }
    private var pendingRecord = false
    private var speakerImport = false
    private var sharing = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.refreshPermissions()
        val record = pendingRecord
        pendingRecord = false
        if (!granted) model.showError(getString(R.string.onboarding_permission_denied))
        else if (record) lifecycleScope.launch {
            val state = model.state.first { it.ready }
            if (state.step == OnboardingStep.VOICE_TRIAL) model.act(OnboardingAction.Record)
        }
    }
    private val modelFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.startTransfer(speakerImport, it) }
    }
    private val audioFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                // Provider diagnostics omit the selected relative path and any
                // audio contents, while making OEM picker differences observable.
                Log.d("Onboarding", "Monitor picker: provider=${uri.authority}, kind=${documentId.substringBefore(':')}, downloads=${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}")
                val path = when (uri.authority) {
                    "com.android.externalstorage.documents" -> LocalAudioFolder.resolve(documentId, Environment.getExternalStorageDirectory().absolutePath)
                    "com.android.providers.downloads.documents" -> LocalAudioFolder.resolveDownloads(documentId,
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                    else -> null
                } ?: error(getString(R.string.onboarding_folder_unsupported))
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                model.selectedFolder(path)
            } catch (error: Exception) { model.showError(error.message ?: getString(R.string.onboarding_folder_unsupported)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRecord = savedInstanceState?.getBoolean("pending_record") ?: false
        speakerImport = savedInstanceState?.getBoolean("speaker_import") ?: false
        model.initialize(intent.getBooleanExtra(EXTRA_REPLAY, false) && savedInstanceState == null)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val dark = when (state.device.preferences.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            UtterlaneTheme(dark) {
                BackHandler { model.act(OnboardingAction.Back) }
                OnboardingScreen(state, ::dispatch)
            }
            SideEffect {
                if (state.trial.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(state.finished, state.exit) {
                if (state.finished) {
                    val target = if (intent.getBooleanExtra(EXTRA_REPLAY, false)) {
                        Intent(this@OnboardingActivity, SettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true)
                    } else io.github.lrq3000.utterlane.home.HomeActivity.intent(this@OnboardingActivity)
                    startActivity(target)
                    finish()
                } else if (state.exit) finish()
            }
        }
    }

    override fun onResume() { super.onResume(); model.refreshPermissions() }
    override fun onStop() {
        if (!isChangingConfigurations) model.stopForBackground()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_record", pendingRecord)
        outState.putBoolean("speaker_import", speakerImport)
        super.onSaveInstanceState(outState)
    }

    private fun dispatch(action: OnboardingAction) {
        try {
            when (action) {
                is OnboardingAction.Permission -> requestPermission(action.permission)
                is OnboardingAction.Settings -> openSettings(action.target)
                is OnboardingAction.Import -> { speakerImport = action.speakers; modelFolder.launch(null) }
                is OnboardingAction.OpenLink -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
                OnboardingAction.ChooseFolder -> audioFolder.launch(null)
                OnboardingAction.ChooseDownloads -> model.selectedFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                OnboardingAction.Record -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingRecord = true
                        permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
                    } else model.act(action)
                }
                OnboardingAction.ShareSample -> shareSample()
                else -> model.act(action)
            }
        } catch (error: Exception) { model.showError(error.message ?: getString(R.string.error_no_browser)) }
    }

    private fun requestPermission(permission: SetupPermission) {
        pendingRecord = false
        val name = when (permission) {
            SetupPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            SetupPermission.AUDIO_FILES -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            SetupPermission.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT < 33) {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    return
                }
                Manifest.permission.POST_NOTIFICATIONS
            }
        }
        permissionRequest.launch(name)
    }

    private fun openSettings(target: AndroidSetup) {
        val intent = when (target) {
            AndroidSetup.APP -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            AndroidSetup.KEYBOARD -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            AndroidSetup.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            AndroidSetup.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        startActivity(intent)
    }

    private fun shareSample() {
        if (sharing) return
        sharing = true
        lifecycleScope.launch {
            try {
                val send = OnboardingSample(this@OnboardingActivity).shareIntent()
                startActivity(Intent.createChooser(send, getString(R.string.onboarding_share_sample)))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { model.showError(error.message ?: getString(R.string.transcribe_error_failed))
            } finally { sharing = false }
        }
    }
}
