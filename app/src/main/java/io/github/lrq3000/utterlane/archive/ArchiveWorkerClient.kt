package io.github.lrq3000.utterlane.archive

import android.app.ActivityManager
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Parent-only, Main-confined IPC. A stuck worker cannot own this control path. */
class ArchiveWorkerClient(private val context: Context, private val connectionTimeoutMs: Long = 15000) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serial = Mutex()
    private var remote: Messenger? = null
    private var connection: ServiceConnection? = null
    private var connected: CompletableDeferred<Unit>? = null
    private var handshake: String? = null
    private var pending: CompletableDeferred<Bundle>? = null
    private var request: String? = null
    private var target: (suspend (String) -> Boolean)? = null
    var workerPid: Int? = null
        private set
    private val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val data = message.data
            if (message.what == ArchiveWire.HELLO && remote != null && data.getString("request") == handshake) {
                workerPid = data.getInt("pid").takeIf { it > 0 && it != Process.myPid() }
                connected?.complete(Unit)
                return
            }
            if (data.getString("request") != request) return
            when (message.what) {
                ArchiveWire.TARGET -> {
                    val id = request
                    val callback = target
                    val response = pending
                    scope.launch {
                        val accepted = try { callback?.invoke(requireNotNull(data.getString("uri"))) == true }
                            catch (e: CancellationException) { response?.cancel(e); throw e }
                            catch (e: Exception) { response?.completeExceptionally(e); false }
                        if (request == id) runCatching { send(ArchiveWire.ACK, Bundle().apply { putString("request", id); putBoolean("accepted", accepted) }) }
                    }
                }
                ArchiveWire.DONE -> pending?.complete(data)
                ArchiveWire.ERROR -> pending?.completeExceptionally(ArchiveWorkerFailure(data.getString("error") ?: "Archival worker failed",
                    if (data.containsKey("creationStarted")) data.getBoolean("creationStarted") else null))
            }
        }
    })

    private suspend fun connect() {
        if (remote != null && connected?.isCompleted == true) return
        val ready = CompletableDeferred<Unit>()
        handshake = UUID.randomUUID().toString()
        connected = ready
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                remote = Messenger(binder)
                try { send(ArchiveWire.HELLO, Bundle().apply { putString("request", handshake) }) } catch (e: Exception) { ready.completeExceptionally(e) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                remote = null; workerPid = null
                val error = java.io.IOException("Archival worker exited; private input is preserved")
                ready.completeExceptionally(error); pending?.completeExceptionally(error)
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        connection = binding
        check(context.bindService(Intent(context, ArchiveWorkerService::class.java), binding, Context.BIND_AUTO_CREATE)) { "Cannot bind archival worker" }
        try { withTimeout(connectionTimeoutMs) { ready.await() } }
        catch (e: TimeoutCancellationException) {
            // A connection deadline is a retryable worker failure, not a user
            // cancellation. Otherwise the durable job would remain RUNNING with
            // no drain and no way to retry it except force-stop/restart.
            if (!currentCoroutineContext().isActive) throw e
            throw ArchiveWorkerFailure("Archival worker did not connect; private input is preserved", creationStarted = false)
        }
    }
    private fun send(what: Int, data: Bundle) {
        checkNotNull(remote).send(Message.obtain(null, what).apply { this.data = data; replyTo = receiver })
    }
    private suspend fun call(what: Int, data: Bundle, onTarget: (suspend (String) -> Boolean)? = null): Bundle = withContext(Dispatchers.Main.immediate) {
        serial.withLock {
            connect()
            val response = CompletableDeferred<Bundle>()
            request = UUID.randomUUID().toString(); pending = response; target = onTarget
            data.putString("request", request)
            try { send(what, data); response.await() }
            finally { pending = null; request = null; target = null }
        }
    }
    suspend fun execute(job: ArchivalJob, onTarget: suspend (String) -> Boolean) {
        require(job.phase == ArchivalPhase.PRIMARY) { "Pending fallback is parent-only; the codec must stay stopped" }
        // Only a private file is opened by the parent, never a provider descriptor.
        var descriptor: ParcelFileDescriptor? = null
        try {
            withContext(Dispatchers.IO) { descriptor = ParcelFileDescriptor.open(job.request.source, ParcelFileDescriptor.MODE_READ_ONLY) }
            val fd = checkNotNull(descriptor)
            val data = ArchiveWire.job(job).apply { putParcelable("input", fd); putLong("bytes", fd.statSize) }
            call(ArchiveWire.RUN, data, onTarget)
        } finally { withContext(NonCancellable + Dispatchers.IO) { descriptor?.close() } }
    }
    suspend fun delete(uri: String): Boolean = call(ArchiveWire.DELETE, Bundle().apply { putString("uri", uri) }).getBoolean("deleted")
    suspend fun find(folder: String, name: String): String? = call(ArchiveWire.FIND, Bundle().apply {
        putString("folder", folder); putString("name", name)
    }).getString("uri")

    /** Called only on Main after cancellation/pause intent is durable. Matching
     * UID + exact process name avoids killing capture, recognition or a reused PID. */
    fun stopAndKill() {
        runCatching { if (remote != null) send(ArchiveWire.STOP, Bundle()) }
        try {
            val processes = context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            processes.filter { it.uid == Process.myUid() && it.processName == "${context.packageName}:archive" &&
                (workerPid == null || workerPid == it.pid) && it.pid != Process.myPid() }.forEach { Process.killProcess(it.pid) }
        } finally {
            // A process-query failure must not strand the binding or waiters.
            // The worker also self-terminates on unbind/parent Binder death.
            val old = connection
            connection = null; remote = null; workerPid = null
            old?.let { runCatching { context.unbindService(it) } }
            connected?.cancel(); connected = null; handshake = null
            pending?.cancel(CancellationException("Archival worker stopped"))
        }
    }
}
