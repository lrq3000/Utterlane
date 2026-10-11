package io.github.lrq3000.utterlane.archive

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** The import's original private copy remains leased by parent ASR. The worker
 * independently rereads it; only derivative PCM staging is bounded/throttled. */
object ImportedAudioEncoder {
    fun encode(context: Context, source: File, spool: RollingPcmJournal, output: File, pcmParts: Int = 0) {
        val original = OriginalAudioSource.open(spool.directory)
        fun asset(part: Int) = original?.asset(part) ?: io.github.lrq3000.utterlane.history.AudioPart(
            if (pcmParts > 0) File(source.parentFile, "audio-$part.wav") else source)
        fun requireUncompressed(part: io.github.lrq3000.utterlane.history.AudioPart) {
        val extractor = MediaExtractor()
        try {
            if (part.readUri == null) extractor.setDataSource(part.local.path)
            else context.contentResolver.openFileDescriptor(android.net.Uri.parse(part.readUri), "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: error("Cannot open original audio")
            val audio = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            check(audio != null && extractor.getTrackFormat(audio).getString(MediaFormat.KEY_MIME) == "audio/raw") {
                "Compressed or unknown imported tracks must be copied unchanged"
            }
        } finally { extractor.release() }
        }
        requireUncompressed(asset(0))
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val decode = scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val accept: suspend (ShortArray) -> Unit = { pcm ->
                    while (spool.state.frames - spool.state.reclaimedFrames > spool.partFrames * 2) {
                        ensureActive(); check(spool.state.encodingAllowed); delay(20)
                        spool.reclaim()
                    }
                    spool.append(pcm)
                    // This decoder coroutine is the ONLY reclamation-cursor
                    // writer for an import. The parent writes a separate stop
                    // fence; no process-local lock coordinates those two files.
                    // No original-PCM ASR consumer reads this derivative spool.
                    spool.commitAsr(spool.format.recognitionSamples(spool.state.frames), 0)
                    spool.reclaim()
                }
                for (part in 0 until (original?.parts ?: pcmParts.coerceAtLeast(1))) {
                    val input = asset(part)
                    if (part != 0) requireUncompressed(input)
                    AudioDecoder(context).decodeTo(input, spool.format, accept)
                }
                spool.closeCapture()
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            ProgressiveAudioEncoder.encode(spool, output) { failure.get()?.let { throw java.io.IOException("Import conversion failed", it) } }
        } finally { runBlocking { decode.cancelAndJoin() }; scope.cancel() }
    }
}
