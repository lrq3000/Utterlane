package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicReference

/**
 * Audio preservation is independent of model availability and throughput. Only
 * the writer consumes the bounded PCM queue; inference follows saved offsets.
 * Exceptions are stage outcomes, not cancellation of healthy sibling stages.
 */
class RecordingPipeline(
    private val source: AudioCapture,
    private val history: RecordingHistory,
    private val recording: RecordingHistory.Recording,
    private val options: RuntimeOptions,
    private val save: (ShortArray) -> Unit = recording::append
) {
    class CaptureCapacityException : IllegalStateException("Audio storage could not keep up with capture")
    data class Outcome(val captureError: Exception?, val storageError: Exception?, val processingError: Exception?)
    private data class Publication(val samples: Long = 0, val done: Boolean = false)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(
        prepare: suspend () -> Unit,
        accept: suspend (ShortArray) -> Unit,
        finish: suspend () -> Unit,
        onSamples: (ShortArray) -> Unit = {},
        onCaptureEnded: suspend () -> Unit = {},
        onProcessingFailed: suspend (Exception) -> Unit = {}
    ): Outcome = coroutineScope {
        val queue = BoundedAudioQueue(options)
        val published = MutableStateFlow(Publication())
        val dispatched = CompletableDeferred<Unit>()
        // One emergency slot preserves the block that discovers a full writer
        // queue. Capture stops, then the writer drains it AFTER the queued prefix.
        // Unlike dropping/blocking the AudioRecord callback, memory stays bounded
        // and every delivered block is retained when storage remains writable.
        val overflow = AtomicReference<ShortArray?>(null)
        var captureError: Exception? = null
        var storageError: Exception? = null
        var processingError: Exception? = null
        val capture = launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                val captureContext = currentCoroutineContext()
                dispatched.complete(Unit)
                source.startRecording(options, { pcm ->
                    if (!queue.offer(pcm)) {
                        check(overflow.compareAndSet(null, pcm)) { "Capture source ignored Stop" }
                        captureError = CaptureCapacityException()
                        source.stop()
                    }
                    onSamples(pcm)
                }, { captureContext.isActive })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { captureError = e
            } finally {
                dispatched.complete(Unit)
                queue.close()
                withContext(NonCancellable) { onCaptureEnded() }
            }
        }
        val writer = launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                // On explicit cancellation, first let capture exit, then finish
                // its bounded in-memory tail. Cancel is not permission to erase
                // PCM that has already left the microphone.
                withContext(NonCancellable) {
                    for (pcm in queue.blocks) {
                        save(pcm)
                        published.value = Publication(recording.writtenSamples)
                    }
                    overflow.getAndSet(null)?.let(save)
                }
            } catch (e: Exception) {
                storageError = e
                source.stop()
            } finally { published.value = Publication(recording.writtenSamples, done = true) }
        }
        try {
            // This runs concurrently with capture/writing, including native load
            // and warm-up. Failure deliberately does not cancel those children.
            dispatched.await()
            try {
                prepare()
                history.openReader(recording.entry.id).use { reader ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val available = published.value
                        if (reader.offset < available.samples) {
                            val pcm = reader.read(minOf(3200L, available.samples - reader.offset).toInt())
                            check(pcm.isNotEmpty()) { "Published recording became unreadable" }
                            accept(pcm)
                        } else if (available.done) break
                        else published.first { it.samples > reader.offset || it.done }
                    }
                }
                finish()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                processingError = e
                onProcessingFailed(e)
            }
            capture.join()
            writer.join()
            Outcome(captureError, storageError, processingError)
        } finally {
            // Cancelling inference must stop its producer before a noncancellable
            // writer drain waits for EOF; otherwise cancellation can deadlock.
            if (!currentCoroutineContext().isActive) source.stop()
            withContext(NonCancellable) { capture.join(); writer.join() }
        }
    }
}
