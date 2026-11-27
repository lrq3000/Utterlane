package io.github.lrq3000.utterlane.service

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.transcribe.AudioMonitorService
import io.github.lrq3000.utterlane.transcribe.QuitNeedsImportGrant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal suspend fun quitUtterlaneWork(
    stopCapture: suspend () -> Unit,
    pauseFiles: suspend () -> Unit,
    pauseArchives: suspend () -> Unit,
    awaitLiveRows: suspend () -> Unit,
    close: () -> Unit
) {
    coroutineScope {
        launch { stopCapture() }
        launch { pauseFiles() }
        launch { pauseArchives() }
    }
    awaitLiveRows()
    close()
}

/** A command on existing owners, not an additional service or an Android package force-stop. */
internal class WorkQuit(private val app: UtterlaneApp) {
    private val mutable = MutableStateFlow(false)
    val inProgress = mutable.asStateFlow()

    fun request() {
        if (!mutable.compareAndSet(false, true)) return
        app.applicationScope.launch {
            val queue = app.transcriptionQueue
            val capture = MicrophoneSession.beginQuit()
            queue.beginQuit()
            app.archival.beginQuit()
            try {
                val message = app.getString(R.string.quit_archive_paused)
                withTimeout(30_000) {
                    quitUtterlaneWork(
                        stopCapture = { coroutineScope { capture.forEach { launch { it.quit() } } } },
                        pauseFiles = queue::pauseFilesForQuit,
                        pauseArchives = { app.archival.stopForQuit(message) },
                        awaitLiveRows = {
                            // Home and floating dictation may have published a final row while their PCM writer was closing.
                            queue.awaitQuitIdle()
                            app.archival.checkpointLateQuitProducers(message)
                        },
                        close = {
                            app.audioPlayback.pauseForCapture()
                            app.stopService(Intent(app, FloatingMicService::class.java))
                            app.stopService(Intent(app, AudioMonitorService::class.java))
                            app.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
                        }
                    )
                }
            } catch (error: TimeoutCancellationException) {
                showFailure(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showFailure(error)
            } finally {
                app.archival.endQuit()
                queue.endQuit()
                MicrophoneSession.endQuit()
                mutable.value = false
            }
        }
    }

    private fun showFailure(error: Exception) {
        Log.w("WorkQuit", "Could not reach a safe shutdown boundary", error)
        val message = app.getString(if (error is QuitNeedsImportGrant) R.string.quit_import_open else R.string.quit_checkpoint_failed)
        app.homeController.showMessage(message)
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}

/** Immutable app-only notification action; existing foreground owners keep the command alive while it checkpoints. */
class WorkQuitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) (context.applicationContext as UtterlaneApp).workQuit.request()
    }

    companion object {
        private const val ACTION = "io.github.lrq3000.utterlane.STOP_WORK_AND_QUIT"
        internal fun addAction(context: Context, notification: NotificationCompat.Builder): NotificationCompat.Builder {
            val intent = Intent(context, WorkQuitReceiver::class.java).setAction(ACTION)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            return notification.addAction(R.drawable.ic_close, context.getString(R.string.quit_stop_all), pending)
        }
    }
}
