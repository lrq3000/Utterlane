package io.github.lrq3000.utterlane.history

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.LocalizedActivity
import io.github.lrq3000.utterlane.settings.SettingsActivity
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme

/** Two full-screen destinations share one Activity and the same browsing contract. */
class HistoryActivity : LocalizedActivity() {
    companion object {
        private const val EXTRA_TRANSCRIPTS = "history_transcripts"
        fun intent(context: Context, transcripts: Boolean = false, internal: Boolean = true): Intent =
            Intent(context, HistoryActivity::class.java).putExtra(EXTRA_TRANSCRIPTS, transcripts)
                .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, internal)
    }
    private val transcripts get() = intent.getBooleanExtra(EXTRA_TRANSCRIPTS, false)
    private val model by viewModels<HistoryViewModel> {
        HistoryViewModel.Factory(application as UtterlaneApp, transcripts)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as UtterlaneApp
        app.historyCleanup.userEntry(intent, savedInstanceState)
        // The retained pager and lazy-list position refer to the same cached
        // window. Recreation alone must not replace it with a differently sized
        // refresh window; visible date labels use the new Activity's locale.
        setContent {
            val theme by app.settingsRepository.themeMode.collectAsStateWithLifecycle(SettingsRepository.THEME_SYSTEM)
            val dark = when (theme) {
                SettingsRepository.THEME_LIGHT -> false
                SettingsRepository.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            UtterlaneTheme(dark) {
                BackHandler(onBack = ::back)
                HistoryScreen(model, transcripts, ::back)
            }
        }
    }

    private fun back() {
        if (isTaskRoot) {
            // A notification may open history without Settings below it. This is
            // still internal navigation, not another launch/retention boundary.
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
        }
        finish()
    }
}
