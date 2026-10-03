package com.translander.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded IPC and finite deadlines; close() never waits for native inference or its mutex. */
class WorkerRecognitionBackend(context: Context, private val model: ModelDefinition) : RecognitionBackend {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val ids = AtomicInteger(0)
    private val replies = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private val connected = CompletableFuture<Messenger>()
    private val identityLock = Any()
    @Volatile var workerPid = 0
        private set
    private var remote: Messenger? = null
    @Volatile private var failureListener: (String) -> Unit = {}
    override fun setFailureListener(listener: (String) -> Unit) { failureListener = listener }
    override fun isAvailable(): Boolean = !closed.get()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        replies.remove(message.arg1)?.complete(message.data)
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
        try { remote = await(connected, 10) }
        catch (failure: Exception) { close(); throw failure }
        val pid = request(RecognitionProtocol.HELLO, Bundle(), 10).getInt("pid")
        synchronized(identityLock) {
            check(!closed.get()) { "Model load was cancelled" }
            check(pid > 0 && pid != Process.myPid()) { "Recognition worker was not isolated" }
            workerPid = pid
        }
        request(RecognitionProtocol.LOAD, Bundle().apply { putString("model", model.id) }, 90)
    }

    override fun transcribeWindow(samples: ShortArray): WindowResult {
        require(samples.size in 1..RecognitionProtocol.MAX_SAMPLES)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.asShortBuffer().put(samples)
        val result = request(RecognitionProtocol.DECODE, Bundle().apply { putByteArray("pcm", pcm.array()) }, 90)
        return WindowResult(requireNotNull(result.getStringArray("tokens")), requireNotNull(result.getFloatArray("timestamps")))
    }

    private fun request(operation: Int, data: Bundle, seconds: Long): Bundle {
        check(!closed.get()) { "Model has been unloaded" }
        val id = ids.incrementAndGet()
        val result = CompletableFuture<Bundle>()
        replies[id] = result
        try {
            // close can race registration; checking again prevents an orphaned wait.
            check(!closed.get()) { "Model has been unloaded" }
            checkNotNull(remote).send(Message.obtain().apply { what = operation; arg1 = id; replyTo = incoming; this.data = data })
            return await(result, seconds).also { it.getString("error")?.let { message -> error(message) } }
        } finally { replies.remove(id) }
    }
    private fun <T> await(future: CompletableFuture<T>, seconds: Long): T = try { future.get(seconds, TimeUnit.SECONDS) }
        catch (e: ExecutionException) { throw IllegalStateException(e.cause?.message ?: "Recognition worker failed", e.cause) }
        catch (e: TimeoutException) { close(); throw IllegalStateException("Recognition exceeded ${seconds}s. Worker unloaded; retry or choose another model.", e) }

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
        replies.values.forEach { it.completeExceptionally(error) }
    }
    private fun unbind() {
        if (bound.compareAndSet(true, false)) try { context.unbindService(connection) } catch (_: IllegalArgumentException) { /* bind failed or already removed */ }
    }
}
