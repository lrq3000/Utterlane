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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Persisted, best-effort age pruning; Android may defer this while asleep. */
class HistoryCleanupService : JobService() {
    private val work = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val job = UtterlaneApp.instance.applicationScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var retry = false
            try {
                val app = UtterlaneApp.instance
                app.historyCleanup.request(if (params.jobId == 2402) HistoryCleanupCoordinator.TEXT else HistoryCleanupCoordinator.AUDIO)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Log.e("HistoryCleanup", "Cannot prune history", e); retry = true
            } finally { work.remove(params.jobId); jobFinished(params, retry) }
        }
        work.put(params.jobId, job)?.cancel()
        job.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work.remove(params.jobId)?.cancel(); return true }

    companion object {
        fun schedule(context: Context, audio: HistoryRetention, text: HistoryRetention) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            for ((id, duration) in listOf(2401 to audio, 2402 to text)) {
                val interval = HistorySchedule.interval(duration)
                if (interval == null) scheduler.cancel(id)
                else if (scheduler.getPendingJob(id)?.intervalMillis != interval) {
                    scheduler.schedule(JobInfo.Builder(id, ComponentName(context, HistoryCleanupService::class.java))
                        .setPersisted(true).setPeriodic(interval).build())
                }
            }
        }
    }
}
