package io.github.lrq3000.utterlane.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.onboarding.OnboardingActivity
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.settings.*
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeActivity : LocalizedActivity() {
    companion object {
        const val EXTRA_DESTINATION = "home_destination"
        fun intent(context: Context, destination: HomeDestination = HomeDestination.RECORD, internal: Boolean = true): Intent =
            Intent(context, HomeActivity::class.java).putExtra(EXTRA_DESTINATION, destination.name)
                .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, internal)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
    private val app get() = application as UtterlaneApp
    private var destination by mutableStateOf(HomeDestination.RECORD)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.homeController.record()
        else app.homeController.microphoneDenied()
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Copy immediately through the existing owned-file import; no broad media,
        // overlay, keyboard or microphone permission is involved in this path.
        uri?.let(app.homeController::load)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.historyCleanup.userEntry(intent, savedInstanceState)
        destination = parseDestination(savedInstanceState?.getString(EXTRA_DESTINATION)
            ?: intent.getStringExtra(EXTRA_DESTINATION)) ?: HomeDestination.RECORD
        lifecycleScope.launch {
            app.modelManager.initializeSelection()
            val configured = app.settingsRepository.hasSavedSettings.first() || app.modelManager.isModelReady()
            if (OnboardingRepository(this@HomeActivity).prepareAutomaticLaunch(configured)) {
                startActivity(Intent(this@HomeActivity, OnboardingActivity::class.java)
                    .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
                finish()
            } else showHome()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun showHome() {
        // Separate cached pagers survive configuration changes; SaveableStateHolder
        // below also retains each HistoryScreen's existing rememberLazyListState.
        val audio = ViewModelProvider(this, HistoryViewModel.Factory(app, false))["home_audio", HistoryViewModel::class.java]
        val text = ViewModelProvider(this, HistoryViewModel.Factory(app, true))["home_transcripts", HistoryViewModel::class.java]
        setContent {
            val theme by app.settingsRepository.themeMode.collectAsStateWithLifecycle(SettingsRepository.THEME_SYSTEM)
            val dark = when (theme) { SettingsRepository.THEME_DARK -> true; SettingsRepository.THEME_LIGHT -> false; else -> isSystemInDarkTheme() }
            UtterlaneTheme(dark) {
                val tabs = rememberSaveableStateHolder()
                BackHandler(destination != HomeDestination.RECORD) { destination = HomeDestination.RECORD }
                Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true },
                    containerColor = MaterialTheme.colorScheme.background,
                    // History already owns a header. The parent can replace it
                    // with HomeHeader without creating a second top bar here.
                    topBar = { if (destination == HomeDestination.RECORD) HomeHeader(onSettings = ::openSettings) },
                    bottomBar = { HomeNavigationBar(destination, { destination = it }) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        tabs.SaveableStateProvider(destination.name) {
                            when (destination) {
                                HomeDestination.RECORD -> HomeScreen(app.homeController, ::record,
                                    onLoad = { audioPicker.launch(arrayOf("audio/*")) }, onSettings = ::openSettings)
                                HomeDestination.AUDIO -> HistoryScreen(audio, false) { destination = HomeDestination.RECORD }
                                HomeDestination.TRANSCRIPTS -> HistoryScreen(text, true) { destination = HomeDestination.RECORD }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun record() {
        if (app.homeController.state.value.canStop || ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) app.homeController.record()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java)
        .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))

    override fun onResume() { super.onResume(); AppEntryServices.restore(this) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app.historyCleanup.userEntry(intent, null)
        parseDestination(intent.getStringExtra(EXTRA_DESTINATION))?.let { destination = it }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_DESTINATION, destination.name)
        super.onSaveInstanceState(outState)
    }
    private fun parseDestination(value: String?) = HomeDestination.entries.firstOrNull { it.name == value }
}
