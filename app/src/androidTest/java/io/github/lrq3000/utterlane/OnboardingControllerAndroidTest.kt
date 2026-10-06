package io.github.lrq3000.utterlane

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.onboarding.*
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
        override fun voiceSession(scope: CoroutineScope, callbacks: OnboardingVoiceTrial.Callbacks) = object : OnboardingTrialSession {
            override fun start() { sessionsStarted.value++ }
            override fun stop() = Unit
            override fun cancel() { sessionsCancelled.value++; callbacks.onClosed() }
        }
    }
}
