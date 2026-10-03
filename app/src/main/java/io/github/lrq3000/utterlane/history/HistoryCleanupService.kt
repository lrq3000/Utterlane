package io.github.lrq3000.utterlane.history

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Persisted, best-effort age pruning; Android may defer this while asleep. */
class HistoryCleanupService : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = UtterlaneApp.instance.applicationScope.launch(Dispatchers.IO) {
            var retry = false
            try {
                val app = UtterlaneApp.instance
                app.recordingHistory.prune(app.settingsRepository.historyRetention.first())
                app.cleanupCacheArtifacts()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Log.e("HistoryCleanup", "Cannot prune history", e); retry = true
            } finally { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }

    companion object {
        fun schedule(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(2401, ComponentName(context, HistoryCleanupService::class.java))
                    .setPersisted(true).setPeriodic(15 * 60 * 1000L).build()
            )
        }
    }
}
