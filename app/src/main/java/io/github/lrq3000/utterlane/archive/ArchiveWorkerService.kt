package io.github.lrq3000.utterlane.archive

import android.app.Service
import android.content.Intent
import android.os.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** All potentially stuck provider/codec work lives here. The main thread only
 * handles control messages; the foreground notification belongs to the parent. */
class ArchiveWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        task.run()
    }, "archive-io") }
    private val busy = AtomicBoolean()
    private var parent: IBinder? = null
    @Volatile private var acknowledgement: Pair<String, CompletableFuture<Boolean>>? = null
    private val death = IBinder.DeathRecipient { Process.killProcess(Process.myPid()) }
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val data = message.data
            val reply = message.replyTo ?: return
            val request = data.getString("request").orEmpty()
            // Handler messages are recycled after this method returns. Only
            // immutable snapshots may cross into the executor.
            val command = message.what
            when (command) {
                ArchiveWire.HELLO -> {
                    if (parent !== reply.binder) {
                        parent?.let { runCatching { it.unlinkToDeath(death, 0) } }
                        parent = reply.binder
                        reply.binder.linkToDeath(death, 0)
                    }
                    respond(reply, ArchiveWire.HELLO, request, Bundle().apply { putInt("pid", Process.myPid()) })
                }
                ArchiveWire.ACK -> acknowledgement?.takeIf { it.first == request }?.second?.complete(data.getBoolean("accepted"))
                ArchiveWire.STOP -> { executor.shutdownNow(); acknowledgement?.second?.complete(false) }
                else -> {
                    if (!busy.compareAndSet(false, true)) { respond(reply, ArchiveWire.ERROR, request, Bundle().apply { putString("error", "Archive worker is busy") }); return }
                    executor.execute {
                        var response = ArchiveWire.DONE
                        var result = Bundle()
                        val provider = SafArtifacts(this@ArchiveWorkerService)
                        try {
                            when (command) {
                                ArchiveWire.RUN -> {
                                    @Suppress("DEPRECATION")
                                    val fd = checkNotNull(data.getParcelable<ParcelFileDescriptor>("input"))
                                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                                        val job = ArchiveWire.job(data)
                                        fun acceptTarget(uri: String): Boolean {
                                            val ack = CompletableFuture<Boolean>()
                                            acknowledgement = request to ack
                                            return try {
                                                respond(reply, ArchiveWire.TARGET, request, Bundle().apply { putString("uri", uri) })
                                                ack.get(30, TimeUnit.SECONDS)
                                             } finally { acknowledgement = null }
                                        }
                                        if (job.request.operation == ArchivalOperation.ENCODE_AUDIO) {
                                            val source = java.io.File(requireNotNull(data.getString("sourcePath")))
                                            check(source.canonicalFile.toPath().startsWith(filesDir.canonicalFile.toPath()) && source.name == "rolling.properties")
                                            val output = java.io.File(source.parentFile, job.targetName)
                                            check(acceptTarget(output.toURI().toString())) { "Encoding publication cancelled" }
                                            EncodingInput.dispatch(checkNotNull(source.parentFile),
                                                live = { spool -> ProgressiveAudioEncoder.encode(spool, output) },
                                                imported = { original, spool, parts -> ImportedAudioEncoder.encode(this@ArchiveWorkerService, original, spool, output, parts) })
                                        } else ArchivalTransfer.execute(job, input, data.getLong("bytes"), provider, target = ::acceptTarget,
                                            checkActive = { if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Archival stopped") })
                                    }
                                }
                                ArchiveWire.DELETE -> result.putBoolean("deleted", provider.delete(requireNotNull(data.getString("uri"))))
                                ArchiveWire.FIND -> result.putString("uri", provider.findExact(requireNotNull(data.getString("folder")), requireNotNull(data.getString("name"))))
                                else -> error("Unsupported archival descriptor")
                            }
                        } catch (e: Exception) {
                            response = ArchiveWire.ERROR
                            result = Bundle().apply {
                                putString("error", (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(1024))
                                if (command == ArchiveWire.RUN) putBoolean("creationStarted", provider.creationStarted)
                            }
                        } finally { busy.set(false) }
                        // Advertise readiness before acknowledging completion: a
                        // fast parent may submit the next descriptor immediately.
                        respond(reply, response, request, result)
                    }
                }
            }
        }
    })
    private fun respond(to: Messenger, what: Int, request: String, data: Bundle) {
        data.putString("request", request)
        runCatching { to.send(Message.obtain(null, what).apply { this.data = data }) }
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { Process.killProcess(Process.myPid()); return false }
    override fun onDestroy() {
        executor.shutdownNow()
        parent?.let { runCatching { it.unlinkToDeath(death, 0) } }
        super.onDestroy()
    }
}
