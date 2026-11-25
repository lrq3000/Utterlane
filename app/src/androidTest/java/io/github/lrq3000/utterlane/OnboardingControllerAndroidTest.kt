package io.github.lrq3000.utterlane

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.CaptureMetrics
import io.github.lrq3000.utterlane.asr.LiveSpeakerControl
import io.github.lrq3000.utterlane.onboarding.*
import io.github.lrq3000.utterlane.ui.RecordingPanel
import io.github.lrq3000.utterlane.ui.WaveformButton
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Exercise controller interleavings on Android's real Main dispatcher/DataStore. */
@RunWith(AndroidJUnit4::class)
class OnboardingControllerAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val repository = OnboardingRepository(app)
    private val store = ViewModelStore()
    private val services = ControlledServices()
    private val ui = OnboardingTestUi()
    private lateinit var original: OnboardingProgress
    private lateinit var model: OnboardingViewModel

    @Before fun prepare() = runBlocking {
        original = repository.progress.first()
        repository.update { OnboardingProgress(initialized = true) }
        instrumentation.runOnMainSync {
            model = OnboardingViewModel(services, repository, app::getString)
            store.put("onboarding-test", model)
        }
    }

    @After fun restore() = runBlocking {
        val job = model.viewModelScope.coroutineContext[Job]
        instrumentation.runOnMainSync { store.clear() }
        job?.join()
        repository.update { original }
    }

    private suspend fun initialize() {
        services.initialization.complete(Unit)
        instrumentation.runOnMainSync { model.initialize(false) }
        withTimeout(5000) { model.state.first { it.ready && !it.busy } }
    }

    @Test fun backgroundingDuringVerificationCannotStartALateRecording() = runBlocking {
        repository.update { it.copy(stepId = OnboardingStep.VOICE_TRIAL.id, modelId = "full") }
        initialize()
        assertTrue(model.state.value.modelReady)
        services.verification = CompletableDeferred()
        instrumentation.runOnMainSync {
            model.act(OnboardingAction.Record)
            model.stopForBackground()
        }
        services.verification.complete(true)
        withTimeout(5000) { services.sessionsStarted.first { it > 0 } }
        instrumentation.waitForIdleSync()
        assertEquals(TrialPhase.IDLE, model.state.value.trial.phase)
        assertEquals(1, services.sessionsCancelled.value)
    }

    @Test fun returnedPickerSelectionsWaitForInitializationInsteadOfBeingDropped() = runBlocking {
        val uri = Uri.parse("content://fixture/speaker-model")
        instrumentation.runOnMainSync {
            model.initialize(false)
            model.startTransfer(true, uri)
            model.selectedFolder("/storage/emulated/0/Download/Chosen")
        }
        services.initialization.complete(Unit)
        withTimeout(5000) { services.imported.first { it != null } }
        withTimeout(5000) { services.folder.first { it != null } }
        assertEquals(true to uri, services.imported.value)
        assertEquals("/storage/emulated/0/Download/Chosen", services.folder.value)
    }

    @Test fun choosingAppearanceDuringStartupKeepsTheFreshDeviceRecommendation() = runBlocking {
        instrumentation.runOnMainSync {
            model.initialize(false)
            model.act(OnboardingAction.Appearance("dark"))
        }
        assertTrue(services.device.value.preferences.previouslyConfigured)
        services.initialization.complete(Unit)
        val state = withTimeout(5000) { model.state.first { it.ready && !it.busy } }
        assertEquals("compact", state.recommendedId)
        assertEquals("compact", state.choice?.id)
    }

    @Test fun initializationCanRetryWithoutLosingAReturnedFolder() = runBlocking {
        services.failInitialization = true
        instrumentation.runOnMainSync {
            model.initialize(false)
            model.selectedFolder("/storage/emulated/0/Download/Retained")
        }
        services.initialization.complete(Unit)
        withTimeout(5000) { model.state.first { it.error != null && !it.busy } }
        services.failInitialization = false
        instrumentation.runOnMainSync { model.initialize(false) }
        withTimeout(5000) { services.folder.first { it != null } }
        assertEquals("/storage/emulated/0/Download/Retained", services.folder.value)
    }

    @Test fun singleSpeakerReplayEnablesLabelsWithoutStartingAModelTransfer(): Unit = runBlocking {
        services.device.value = services.device.value.copy(preferences = OnboardingPreferences(speakerCount = 1))
        services.verification = CompletableDeferred(false) // No auxiliary model is installed.
        repository.update { it.copy(stepId = OnboardingStep.SPEAKERS.id, modelId = "full", speakersWanted = true) }
        initialize()
        instrumentation.runOnMainSync { model.act(OnboardingAction.Next) }
        withTimeout(5000) { model.state.first { !it.busy && it.step == OnboardingStep.VOICE_TRIAL } }
        assertTrue(services.speakersEnabled.value)
        assertNull(services.imported.value)
        instrumentation.runOnMainSync { model.act(OnboardingAction.Back) }
        withTimeout(5000) { model.state.first { !it.busy && it.step == OnboardingStep.SPEAKERS } }
    }

    @Test fun voiceTrialUsesKeyboardWaveformThroughProcessing(): Unit = runBlocking {
        withTrialScreen { activity ->
            instrumentation.runOnMainSync { model.act(OnboardingAction.EditText("Typed before dictation.")) }
            startTrial()
            instrumentation.runOnMainSync {
                services.voice!!.apply {
                    metrics.model("Practice speech model")
                    beginCapture()
                    repeat(64) { index ->
                        val amplitude = 400 * (1 + (index * 7 % 32))
                        metrics.samples(ShortArray(800) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }, true)
                    }
                    metrics.preparing(false) // Publish the bounded sample batch without waiting for the QA clock.
                    callbacks.onText("Recognized during capture.")
                }
            }
            val waveformId = "${app.packageName}:id/recording_done"
            ui.scrollTo(waveformId)
            val liveField = ui.node("onboarding_transcript")
            try {
                val visible = Rect().also(liveField::getBoundsInScreen)
                assertTrue("Leave at least one line of practice text above the keyboard panel",
                    liveField.isVisibleToUser && visible.height() >= 32 * activity.resources.displayMetrics.density)
            } finally { liveField.recycle() }
            instrumentation.runOnMainSync {
                val waveform = activity.findViewById<WaveformButton>(R.id.recording_done)
                val panel = generateSequence<View>(waveform) { it.parent as? View }.filterIsInstance<RecordingPanel>().first()
                assertTrue("Waveform must reflect actual samples", waveform.levels.any { it > 0f })
                assertEquals("Recognized during capture.", panel.statusText.text.toString())
                val speakers = panel.findViewById<android.widget.ToggleButton>(R.id.recording_speakers)
                assertEquals(View.VISIBLE, speakers.visibility)
                speakers.performClick()
                assertEquals(true, services.voice!!.speakers.state.value)
            }
            ui.screenshot("onboarding-keyboard-waveform")
            ui.click(waveformId, scroll = false)
            withTimeout(5000) { model.state.first { it.trial.phase == TrialPhase.PROCESSING } }
            ui.textNode(activity.getString(R.string.capture_processing)).recycle()
            instrumentation.runOnMainSync {
                services.voice!!.callbacks.onText("Recognized during capture. Final words.")
                services.voice!!.callbacks.onComplete(null)
            }
            withTimeout(5000) {
                var removed = false
                while (!removed) {
                    instrumentation.runOnMainSync { removed = activity.findViewById<View>(R.id.recording_done) == null }
                    if (!removed) delay(20)
                }
            }
            ui.scrollTo("onboarding_transcript", backwards = true)
            val result = ui.node("onboarding_transcript")
            try {
                assertEquals("Typed before dictation. Recognized during capture. Final words.", result.text.toString())
                assertTrue("The practice text must be editable again", result.isEditable)
            } finally { result.recycle() }
        }
    }

    @Test fun cancelClosesThePanelAndTheNextTrialStartsWithAnEmptyPreview(): Unit = runBlocking {
        withTrialScreen { activity ->
            startTrial()
            instrumentation.runOnMainSync {
                services.voice!!.apply {
                    beginCapture()
                    callbacks.onText("Words from the first trial.")
                }
            }
            val waveformId = "${app.packageName}:id/recording_done"
            ui.scrollTo(waveformId)
            val first = services.voice!!
            instrumentation.runOnMainSync { first.deferClose = true }
            ui.clickText(activity.getString(R.string.overlay_cancel))
            withTimeout(5000) { model.state.first { !it.trial.active && it.trial.releasing } }
            ui.scrollTo("onboarding_record")
            instrumentation.runOnMainSync { assertNull(activity.findViewById<View>(R.id.recording_done)) }
            val mic = ui.node("onboarding_record")
            try { assertFalse("Do not offer an immediate retry during microphone teardown", mic.isEnabled) }
            finally { mic.recycle() }

            instrumentation.runOnMainSync { first.callbacks.onClosed() }
            withTimeout(5000) { model.state.first { !it.trial.releasing } }
            instrumentation.waitForIdleSync()
            startTrial()
            instrumentation.runOnMainSync { services.voice!!.beginCapture() }
            ui.scrollTo(waveformId)
            instrumentation.runOnMainSync {
                val status = activity.findViewById<android.widget.TextView>(R.id.recording_status)
                assertEquals("", status.text.toString())
            }
        }
    }

    private suspend fun withTrialScreen(test: suspend (OnboardingActivity) -> Unit) {
        ui.prepare()
        val activity = launchNativeActivity(Intent(app, OnboardingActivity::class.java)) as OnboardingActivity
        try {
            repository.update { it.copy(stepId = OnboardingStep.VOICE_TRIAL.id, modelId = "full") }
            initialize()
            instrumentation.runOnMainSync {
                activity.setContent {
                    val state by model.state.collectAsStateWithLifecycle()
                    UtterlaneTheme(darkTheme = false) { OnboardingScreen(state, model::act) }
                }
            }
            test(activity)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private suspend fun startTrial() {
        ui.scrollTo("onboarding_record")
        ui.click("onboarding_record", scroll = false)
        withTimeout(5000) { model.state.first { it.trial.phase == TrialPhase.PREPARING } }
    }

    private class ControlledServices : OnboardingServices {
        val initialization = CompletableDeferred<Unit>()
        var failInitialization = false
        var verification = CompletableDeferred(true)
        val imported = MutableStateFlow<Pair<Boolean, Uri?>?>(null)
        val folder = MutableStateFlow<String?>(null)
        val sessionsStarted = MutableStateFlow(0)
        val sessionsCancelled = MutableStateFlow(0)
        val speakersEnabled = MutableStateFlow(false)
        val device = MutableStateFlow(OnboardingDeviceState(
            models = listOf(
                OnboardingModel("full", "Full", 100, ModelExplanation.ULTRA_Q8, ModelTier.FULL),
                OnboardingModel("compact", "Compact", 10, ModelExplanation.REDUX, ModelTier.COMPACT)),
            selectedModelId = "full", ramBytes = 1L shl 30,
            permissions = OnboardingPermissions(microphone = true),
            speechTransfer = OnboardingTransfer(TransferPhase.READY, 100)))
        override fun observe(scope: CoroutineScope): StateFlow<OnboardingDeviceState> = device
        override suspend fun initialize() {
            initialization.await()
            check(!failInitialization) { "Initialization unavailable" }
            device.value = device.value.copy(initialized = true)
        }
        override fun refreshPermissions() = Unit
        override suspend fun selectModel(id: String) { device.value = device.value.copy(selectedModelId = id) }
        override suspend fun transfer(speakers: Boolean, folder: Uri?) { imported.value = speakers to folder }
        override suspend fun verify(speakers: Boolean): Boolean = verification.await()
        override fun cancelTransfer(speakers: Boolean) = Unit
        override suspend fun setTheme(mode: String) {
            device.value = device.value.copy(preferences = device.value.preferences.copy(theme = mode, previouslyConfigured = true))
        }
        override suspend fun setSpeakers(enabled: Boolean) { speakersEnabled.value = enabled }
        override suspend fun setMonitor(enabled: Boolean) = Unit
        override suspend fun addFolder(path: String) { folder.value = path }
        override suspend fun removeFolder(path: String) { if (folder.value == path) folder.value = null }
        override suspend fun enableFloating() = Unit
        var voice: ControlledVoiceSession? = null
        override fun voiceSession(scope: CoroutineScope, callbacks: OnboardingVoiceTrial.Callbacks) =
            ControlledVoiceSession(callbacks).also { voice = it }

        inner class ControlledVoiceSession(val callbacks: OnboardingVoiceTrial.Callbacks) : OnboardingTrialSession {
            val metrics = CaptureMetrics { 0L }
            val speakers = LiveSpeakerControl()
            override val panel = VoiceTrialPanel(metrics.state, speakers)
            var deferClose = false
            fun beginCapture() { metrics.started(); callbacks.onReady() }
            override fun start() { sessionsStarted.value++ }
            override fun stop() { metrics.captureEnded(); callbacks.onCaptureEnded() }
            override fun cancel(discard: Boolean) {
                sessionsCancelled.value++
                if (!deferClose) callbacks.onClosed()
            }
        }
    }
}
