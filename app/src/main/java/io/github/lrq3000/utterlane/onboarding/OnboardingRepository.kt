package io.github.lrq3000.utterlane.onboarding

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.onboardingStore by preferencesDataStore("onboarding")

/** Wizard bookkeeping lives separately from the app's durable feature settings. */
class OnboardingRepository(context: Context) {
    private val store = context.applicationContext.onboardingStore
    private object Keys {
        val initialized = booleanPreferencesKey("initialized")
        val completed = booleanPreferencesKey("completed")
        val step = stringPreferencesKey("step")
        val model = stringPreferencesKey("model")
        val speakers = booleanPreferencesKey("speakers_wanted")
        val monitor = booleanPreferencesKey("monitor_wanted")
    }
    val progress = store.data.map(::decode).distinctUntilChanged()

    private fun decode(values: Preferences) = OnboardingProgress(
        values[Keys.initialized] ?: false, values[Keys.completed] ?: false,
        values[Keys.step] ?: OnboardingStep.WELCOME.id, values[Keys.model],
        values[Keys.speakers] ?: false, values[Keys.monitor] ?: false
    )

    suspend fun update(change: (OnboardingProgress) -> OnboardingProgress) {
        store.edit { values ->
            val updated = change(decode(values))
            values[Keys.initialized] = updated.initialized
            values[Keys.completed] = updated.completed
            values[Keys.step] = updated.stepId
            updated.modelId?.let { values[Keys.model] = it } ?: values.remove(Keys.model)
            values[Keys.speakers] = updated.speakersWanted
            values[Keys.monitor] = updated.monitorWanted
        }
    }

    suspend fun prepareAutomaticLaunch(existingConfiguration: Boolean): Boolean {
        var launch = false
        update { current ->
            launch = current.shouldLaunch(existingConfiguration)
            if (current.initialized) current
            else current.copy(initialized = true, completed = !launch)
        }
        return launch
    }

    suspend fun begin(replay: Boolean, modelId: String, speakers: Boolean, monitor: Boolean) {
        update { current ->
            when {
                replay -> current.copy(initialized = true, stepId = OnboardingStep.WELCOME.id,
                    modelId = modelId, speakersWanted = speakers, monitorWanted = monitor)
                current.modelId == null -> current.copy(initialized = true, modelId = modelId,
                    speakersWanted = speakers, monitorWanted = monitor)
                else -> current.copy(initialized = true)
            }
        }
    }

    suspend fun complete() = update { it.copy(initialized = true, completed = true, stepId = OnboardingStep.COMPLETE.id) }
}
