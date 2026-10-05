package io.github.lrq3000.utterlane.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded IPC and configurable progress budgets; close() never waits for native inference. */
class WorkerRecognitionBackend(context: Context, private val model: ModelDefinition) : RecognitionBackend {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val ids = AtomicInteger(0)
    private val replies = RecognitionRequests<Bundle>()
    private val connected = CompletableFuture<Messenger>()
    private val identityLock = Any()
    private val activityLock = Any()
    private var activityRequest = 0
    @Volatile private var options = RuntimeOptions()
    @Volatile private var activityListener: (RecognitionActivity) -> Unit = {}
    private val power = this.context.getSystemService(PowerManager::class.java)
    private fun paused() = !power.isInteractive || power.isDeviceIdleMode
    override fun configure(options: RuntimeOptions) { this.options = options.requireValid() }
    override fun setActivityListener(listener: (RecognitionActivity) -> Unit) { activityListener = listener }
    @Volatile var workerPid = 0
        private set
    private var remote: Messenger? = null
    @Volatile private var failureListener: (String) -> Unit = {}
    override fun setFailureListener(listener: (String) -> Unit) { failureListener = listener }
    override fun isAvailable(): Boolean = !closed.get()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!closed.get()) when (message.what) {
            RecognitionProtocol.RESULT -> replies.complete(message.arg1, message.data)
            RecognitionProtocol.PROGRESS -> {
                val data = message.data
                replies.progress(RecognitionProgress(message.arg1, data.getLong("sequence"), data.getString("stage").orEmpty(),
                    data.getLong("units"), data.getBoolean("completed"), data.getBoolean("opaque", true)),
                    SystemClock.uptimeMillis(), paused())
            }
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed.get()) { unbind(); return }
            try {
                service.linkToDeath({ workerFailed("Recognition worker exited; retry or select another model") }, 0)
                connected.complete(Messenger(service))
            } catch (e: RemoteException) { workerFailed(e.message ?: "Recognition worker unavailable") }
        }
        override fun onServiceDisconnected(name: ComponentName) { workerFailed("Recognition worker disconnected") }
        override fun onBindingDied(name: ComponentName) { workerFailed("Recognition worker binding died") }
        override fun onNullBinding(name: ComponentName) { workerFailed("Recognition worker unavailable") }
    }

    override fun prepare() {
        val snapshot = options
        check(Looper.myLooper() != Looper.getMainLooper()) { "Model initialization cannot block the UI" }
        check(!closed.get()) { "Model load was cancelled" }
        synchronized(identityLock) {
            check(!closed.get()) { "Model load was cancelled" }
            bound.set(true)
            if (!context.bindService(Intent(context, RecognitionWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                bound.set(false); error("Could not start recognition worker")
            }
        }
        if (closed.get()) { unbind(); error("Model load was cancelled") }
        val connectId = ids.incrementAndGet()
        val connectWatchdog = watchdog(connectId, snapshot.workerConnectSeconds, snapshot.absoluteOperationSeconds)
        connectWatchdog.accept(RecognitionProgress(connectId, 1, "connecting", 0), SystemClock.uptimeMillis(), paused())
        beginActivity(connectWatchdog)
        try { remote = await(connected, connectWatchdog) }
        catch (failure: Exception) { close(); throw failure }
        val pid = request(RecognitionProtocol.HELLO, Bundle(), snapshot.workerConnectSeconds, snapshot.absoluteOperationSeconds).getInt("pid")
        synchronized(identityLock) {
            check(!closed.get()) { "Model load was cancelled" }
            check(pid > 0 && pid != Process.myPid()) { "Recognition worker was not isolated" }
            workerPid = pid
        }
        request(RecognitionProtocol.LOAD, Bundle().apply {
            putString("model", model.id); putBundle("options", OptionsCodec.toBundle(snapshot))
        }, snapshot.prepareStallSeconds, snapshot.absoluteOperationSeconds)
    }

    override fun transcribeWindow(samples: ShortArray): WindowResult {
        val snapshot = options
        val result = request(RecognitionProtocol.DECODE, pcmBundle(samples), snapshot.inferenceStallSeconds, snapshot.absoluteOperationSeconds)
        return WindowResult(requireNotNull(result.getStringArray("tokens")), requireNotNull(result.getFloatArray("timestamps")), result.getString("text"), result.getFloatArray("ends") ?: floatArrayOf())
    }
    private fun pcmBundle(samples: ShortArray): Bundle {
        require(samples.size in 1..RecognitionProtocol.MAX_SAMPLES)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.asShortBuffer().put(samples)
        return Bundle().apply { putByteArray("pcm", pcm.array()) }
    }
    override fun transcribeSpeakers(sessionId: Long, window: AudioWindow, count: Int, options: RuntimeOptions): List<SpeechSpan> {
        val snapshot = this.options
        val result = request(RecognitionProtocol.SPEAKERS, pcmBundle(window.samples).apply {
            putLong("session", sessionId); putInt("count", count); putLong("start", window.startSample)
            putLong("ownedStart", window.ownedStart); putLong("ownedEnd", window.ownedEnd); putBoolean("final", window.isFinal)
            putBundle("options", OptionsCodec.toBundle(options))
        }, snapshot.inferenceStallSeconds, snapshot.absoluteOperationSeconds)
        val texts = requireNotNull(result.getStringArray("texts"))
        val speakers = requireNotNull(result.getIntArray("speakers"))
        check(texts.size == speakers.size && speakers.all { it in -1..7 })
        return texts.indices.map { SpeechSpan(texts[it], speakers[it]) }
    }
    override fun endSession(sessionId: Long) {
        // Fire-and-forget cleanup is queued behind any cancelled in-flight JNI
        // operation. Closing a recording never waits on the Android main thread.
        if (!closed.get()) try {
            remote?.send(Message.obtain().apply { what = RecognitionProtocol.END_SESSION; data = Bundle().apply { putLong("session", sessionId) } })
        } catch (_: RemoteException) { /* Process exit has already reclaimed it. */ }
    }

    private fun watchdog(id: Int, stallSeconds: Long, absoluteSeconds: Long) =
        ProgressWatchdog(id, stallSeconds * 1000, absoluteSeconds * 1000, SystemClock.uptimeMillis(), paused())

    private fun request(operation: Int, data: Bundle, stallSeconds: Long, absoluteSeconds: Long): Bundle {
        check(!closed.get()) { "Model has been unloaded" }
        val id = ids.incrementAndGet()
        val pending = replies.register(id, watchdog(id, stallSeconds, absoluteSeconds))
        beginActivity(pending.watchdog)
        var failure: String? = null
        try {
            // close can race registration; checking again prevents an orphaned wait.
            check(!closed.get()) { "Model has been unloaded" }
            checkNotNull(remote).send(Message.obtain().apply { what = operation; arg1 = id; replyTo = incoming; this.data = data })
            return await(pending.result, pending.watchdog).also { it.getString("error")?.let { message -> error(message) } }
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
            throw error
        } finally {
            replies.remove(id)
            publish(pending.watchdog.activity().copy(active = false, stage = if (failure == null) "completed" else "error", message = failure))
        }
    }
    private fun <T> await(future: CompletableFuture<T>, watchdog: ProgressWatchdog): T {
        while (true) {
            try { return future.get(250, TimeUnit.MILLISECONDS) }
            catch (e: InterruptedException) {
                // Cancel this wait, not the shared worker: other sessions still
                // own it. request() removes this reply ID; the serialized worker
                // may finish its current decode before serving the next request.
                // Initialization cancellation is closed by RecognizerManager.
                throw e
            }
            catch (e: ExecutionException) { throw IllegalStateException(e.cause?.message ?: "Recognition worker failed", e.cause) }
            catch (e: TimeoutException) {
                // uptime excludes deep sleep; the explicit pause also covers a
                // frozen worker while the main process remains awake. Poll the
                // existing request, never resend PCM and duplicate its result.
                val reason = watchdog.expiration(SystemClock.uptimeMillis(), paused())
                if (reason != null && !future.isDone) {
                    val detail = when (reason) {
                        ProgressWatchdog.Expiration.PROGRESS_STALL -> "Progress stall: no new completed computational work within the configured awake-time limit."
                        ProgressWatchdog.Expiration.ABSOLUTE_BUDGET -> "Absolute active-time budget exhausted, even if computation was progressing."
                        ProgressWatchdog.Expiration.UNAVAILABLE_OPAQUE -> "Computational progress unavailable for this stage; the configured awake-time fallback expired. This does not establish a native freeze."
                    }
                    val message = "$detail Worker unloaded. Adjust recovery limits in Advanced settings (0 disables a limit)."
                    if (closed.compareAndSet(false, true)) {
                        disconnect(message)
                        failureListener(message)
                    }
                    throw IllegalStateException(message, e)
                }
                // Only this 250 ms client loop publishes live UI status. Incoming
                // packets update pure counters; observers never inspect inference JNI.
                publish(watchdog.activity())
            }
        }
    }

    private fun beginActivity(watchdog: ProgressWatchdog) = synchronized(activityLock) {
        if (!closed.get()) {
            val activity = watchdog.activity()
            activityRequest = activity.requestId
            activityListener(activity)
        }
    }

    private fun publish(activity: RecognitionActivity) = synchronized(activityLock) {
        // Cancellation can leave an old waiter's finally block racing a new request.
        if (!closed.get() && activity.requestId == activityRequest) activityListener(activity)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnect("Model forcibly unloaded")
    }
    private fun workerFailed(message: String) {
        if (!closed.compareAndSet(false, true)) return
        // A dead Binder cannot be repaired by reusing its completed connect future.
        // Clear the PID before disconnect: it may already have been recycled.
        synchronized(identityLock) { workerPid = 0 }
        disconnect(message)
        failureListener(message)
    }
    private fun disconnect(message: String) {
        failPending(IllegalStateException(message))
        synchronized(activityLock) {
            activityRequest = 0
            activityListener(RecognitionActivity(stage = "idle", message = message))
        }
        synchronized(identityLock) {
            // The private, non-exported service supplied this same-UID worker PID.
            // OS process termination releases model memory even when JNI is stuck.
            if (workerPid > 0) Process.killProcess(workerPid)
            workerPid = 0
        }
        unbind()
    }
    private fun failPending(error: Throwable) {
        connected.completeExceptionally(error)
        replies.fail(error)
    }
    private fun unbind() {
        if (bound.compareAndSet(true, false)) try { context.unbindService(connection) } catch (_: IllegalArgumentException) { /* bind failed or already removed */ }
    }
}
