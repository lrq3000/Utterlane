package io.github.lrq3000.utterlane.transcribe

internal class QueueJobControls(val stage: QueueStage, val pause: () -> Unit, val resume: () -> Unit,
    val stop: () -> Unit, val restart: () -> Unit, val delete: () -> Unit)

internal class FilePauseBoundary : Exception()

/** The checkpoint counts decoded 16 kHz input, not container timestamps. Skipping decoded samples on resume also works for containers with inaccurate seek tables and variable-rate audio. No previous audio is sent through recognition twice. */
internal class ResumeCursor(private val checkpoint: Long) {
    var position = 0L
        private set
    private val empty = ShortArray(0)
    fun seekToCheckpoint() { position = checkpoint }
    fun unprocessed(samples: ShortArray): ShortArray {
        val skip = (checkpoint - position).coerceIn(0L, samples.size.toLong()).toInt()
        position += samples.size
        return when (skip) {
            0 -> samples
            samples.size -> empty
            else -> samples.copyOfRange(skip, samples.size)
        }
    }
    fun requireReached() { check(position >= checkpoint) { "Source ended before the saved transcription position" } }
}
