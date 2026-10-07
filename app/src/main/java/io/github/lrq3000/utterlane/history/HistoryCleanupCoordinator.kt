package io.github.lrq3000.utterlane.history

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.lrq3000.utterlane.UtterlaneApp
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Startup and scheduled housekeeping, never a simulated application-close hook. */
class HistoryCleanupCoordinator(private val app: UtterlaneApp) {
    companion object {
        const val AUDIO = 1
        const val TEXT = 2
        const val BOTH = AUDIO or TEXT
        const val INTERNAL_NAVIGATION = "utterlane_internal_navigation"
    }
    private val preferences = app.getSharedPreferences("history_launch", Context.MODE_PRIVATE)
    @Volatile var launchToken: String = preferences.getString("token", null) ?: "before-user-launch"
        private set
    private var seenUserEntry = false
    private val mutex = Mutex()
    private val pending = AtomicInteger(0)

    /** Jobs also start Application.onCreate, so only real entry activities call this. */
    fun userEntry(intent: Intent, restored: Bundle?) {
        if (intent.getBooleanExtra(INTERNAL_NAVIGATION, false) || intent.getBooleanExtra(RecordingRecovery.EXTRA_MODELS, false)) return
        if (seenUserEntry && restored != null) return
        seenUserEntry = true
        launchToken = UUID.randomUUID().toString()
        preferences.edit().putString("token", launchToken).apply()
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    // Never release a hold created by a newer entry while this
                    // older request was waiting for the metadata lock.
                    app.recordingHistory.onUserLaunch(launchToken)
                    app.transcriptHistory.onUserLaunch(launchToken)
                }
                request(BOTH)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { android.util.Log.e("HistoryCleanup", "Startup cleanup failed", e) }
        }
    }

    suspend fun request(kinds: Int) {
        pending.getAndUpdate { it or kinds }
        mutex.withLock {
            while (true) {
                val requested = pending.getAndSet(0)
                if (requested == 0) break
                if (requested and AUDIO != 0) app.recordingHistory.prune(app.settingsRepository.audioHistoryRetention.first())
                if (requested and TEXT != 0) app.transcriptHistory.prune(app.settingsRepository.transcriptHistoryRetention.first())
                app.cleanupCacheArtifacts()
            }
        }
    }
}
