package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Capture and persistence own the input; recognition is an independently fallible reader. */
class MicrophonePipeline(
    private val recorder: AudioCapture,
    private val audio: Audio,
    private val options: RuntimeOptions,
    private val prepare: suspend () -> Consumer,
    private val onSamples: (ShortArray) -> Unit = {},
    private val onCaptureEnded: suspend () -> Unit = {},
    private val onRecognitionReady: () -> Unit = {},
    private val onRecognitionFailure: (Failure) -> Unit = {}
) {
    interface Audio {
        val samples: Long
        fun append(samples: ShortArray)
        fun read(offset: Long, count: Int): ShortArray
    }
    interface Consumer : Closeable {
        suspend fun accept(samples: ShortArray)
        suspend fun finish()
    }
    enum class Stage { PREPARATION, INFERENCE, AUDIO, STORAGE, CAPACITY }
    data class Failure(val stage: Stage, val cause: Exception)
    data class Outcome(val failure: Failure?)
    private data class Published(val samples: Long, val done: Boolean = false)

    fun stop() = recorder.stop()

    suspend fun run(): Outcome {
        try {
            return coroutineScope {
                val owner = currentCoroutineContext()
                val captureFailure = AtomicReference<Failure?>(null)
                val recognitionFailure = AtomicReference<Failure?>(null)
                val overflow = AtomicReference<ShortArray?>(null)
                val queue = BoundedAudioQueue(options)
                val published = MutableStateFlow(Published(0))
                fun recognitionFailed(failure: Failure) {
                    if (recognitionFailure.compareAndSet(null, failure)) onRecognitionFailure(failure)
                }

                val writer = launch(Dispatchers.IO) {
                    try {
                        for (samples in queue.blocks) {
                            audio.append(samples)
                            published.value = Published(audio.samples)
                        }
                        // A slow disk may exhaust the bounded scheduling cushion. Stop
                        // visibly, but still save the already-read block that hit its limit.
                        overflow.get()?.let { audio.append(it) }
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        captureFailure.set(Failure(Stage.STORAGE, e))
                        recorder.stop()
                    } finally { published.value = Published(audio.samples, done = true) }
                }
                val capture = launch(Dispatchers.IO) {
                    try {
                        val captureContext = currentCoroutineContext()
                        recorder.startRecording(options, { samples ->
                            if (!queue.offer(samples)) {
                                overflow.compareAndSet(null, samples)
                                captureFailure.compareAndSet(null, Failure(Stage.CAPACITY,
                                    IllegalStateException("Audio storage cannot keep up with capture")))
                                recorder.stop()
                            }
                            onSamples(samples)
                        }, { captureContext.isActive })
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        captureFailure.compareAndSet(null, Failure(Stage.AUDIO, e))
                    } finally {
                        queue.close()
                        withContext(NonCancellable) { onCaptureEnded() }
                    }
                }
                val recognition = launch(Dispatchers.IO) {
                    var consumer: Consumer? = null
                    var stage = Stage.PREPARATION
                    try {
                        consumer = prepare()
                        onRecognitionReady()
                        var offset = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val snapshot = published.value
                            if (offset < snapshot.samples) {
                                stage = Stage.STORAGE
                                val samples = audio.read(offset, minOf(3200L, snapshot.samples - offset).toInt())
                                check(samples.isNotEmpty()) { "Audio reader made no progress" }
                                stage = Stage.INFERENCE
                                consumer.accept(samples)
                                offset += samples.size
                            } else if (snapshot.done) break
                            else published.first { it.samples > offset || it.done }
                        }
                        stage = Stage.INFERENCE
                        consumer.finish()
                    } catch (e: CancellationException) {
                        // Cancelling a recognition owner is not permission to lose the
                        // microphone input. Whole-operation cancellation still propagates.
                        if (!owner.isActive) throw e
                        recognitionFailed(Failure(stage, e))
                    } catch (e: Exception) {
                        recognitionFailed(Failure(stage, e))
                    } finally {
                        try { consumer?.close() }
                        catch (e: Exception) { recognitionFailed(Failure(Stage.INFERENCE, e)) }
                    }
                }
                capture.join()
                writer.join()
                recognition.join()
                Outcome(captureFailure.get() ?: recognitionFailure.get())
            }
        } finally { recorder.stop() }
    }
}
