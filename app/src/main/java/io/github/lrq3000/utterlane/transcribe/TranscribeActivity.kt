package io.github.lrq3000.utterlane.transcribe

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme

/** All audio entry points share a retained operation owner and the same dialog actions. */
class TranscribeActivity : io.github.lrq3000.utterlane.settings.LocalizedActivity() {
    companion object {
        const val ACTION_TRANSCRIBE = "io.github.lrq3000.utterlane.action.TRANSCRIBE"
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_AUDIO_ID = "history_id"
        const val EXTRA_TRANSCRIPT_ID = "transcript_id"
        const val EXTRA_RECOVERY_ID = "recording_recovery_id"
    }
    private lateinit var model: TranscriptionDialogModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the platform's dialog dimming, but make its content area available
        // to the nearly full-height reader instead of the theme's 85% minimum.
        window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
        val app = UtterlaneApp.instance
        app.historyCleanup.userEntry(intent, savedInstanceState)
        setFinishOnTouchOutside(false)
        val audio = savedInstanceState?.getString("owned_audio") ?: intent.getStringExtra(EXTRA_AUDIO_ID) ?: intent.getStringExtra(EXTRA_RECOVERY_ID)
        val textId = if (savedInstanceState?.containsKey("saved_text") == true) savedInstanceState.getString("saved_text") else intent.getStringExtra(EXTRA_TRANSCRIPT_ID)
        val request = DialogInput(extractAudioUri(intent), intent.getStringExtra(EXTRA_FILE_PATH), audio, textId,
            savedInstanceState?.getString("working_text") ?: intent.getStringExtra("transcript_path"),
            automatic = savedInstanceState == null && audio == null && textId == null && !intent.hasExtra("transcript_path"),
            transcriptOrigin = savedInstanceState?.getBoolean("text_origin") ?: intent.hasExtra(EXTRA_TRANSCRIPT_ID),
            modelName = savedInstanceState?.getString("result_model").orEmpty(), modelId = savedInstanceState?.getString("result_model_id"),
            metadata = TranscriptMetadata.fromBundle(savedInstanceState))
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TranscriptionDialogModel(app, request) as T
        })[TranscriptionDialogModel::class.java]
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { model.dismiss { finish() } }
        })
        setContent {
            val theme by app.settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = SettingsRepository.THEME_SYSTEM)
            val dark = when (theme) { SettingsRepository.THEME_DARK -> true; SettingsRepository.THEME_LIGHT -> false; else -> isSystemInDarkTheme() }
            UtterlaneTheme(darkTheme = dark) {
                TranscriptionDialog(model, onClose = { model.dismiss { finish() } }, onEmpty = { finish() })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::model.isInitialized) model.saveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun extractAudioUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> parcelUri(intent, Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> intent.data
        ACTION_TRANSCRIBE -> parcelUri(intent, EXTRA_AUDIO_URI) ?: intent.data
        else -> null
    }
    @Suppress("DEPRECATION") private fun parcelUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java) else intent.getParcelableExtra(key)

    override fun onDestroy() {
        super.onDestroy()
        // Activity destruction is not explicit dismissal. The ViewModel releases
        // its runtime owners while disk-backed recovery survives unexpected loss.
        getSystemService(NotificationManager::class.java).cancel(AudioMonitorService.AUDIO_DETECTED_NOTIFICATION_ID)
    }
}
