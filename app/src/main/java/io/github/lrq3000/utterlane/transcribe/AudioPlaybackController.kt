package io.github.lrq3000.utterlane.transcribe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.HistoryEntry
import io.github.lrq3000.utterlane.settings.VisualRefreshRate
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AudioPlaybackState(val owner: String? = null, val audioId: String? = null, val active: Boolean = false,
    val playing: Boolean = false, val preparing: Boolean = false, val positionMs: Long = 0,
    val durationMs: Long = 0, val error: String? = null)

/**
 * Main-thread-confined local playback, shared across dialogs. Each dialog owns a
 * token, so teardown of an older screen cannot stop a newer screen's playback.
 * Only the active player holds a source lease; sharing/transcription own theirs.
 */
class AudioPlaybackController(private val app: UtterlaneApp) {
    private val mutable = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = mutable
    private var player: MediaPlayer? = null
    private var entry: HistoryEntry? = null
    private var lease: Closeable? = null
    private var timeline: AudioTimeline? = null
    private var preparation: Job? = null
    private var ticker: Job? = null
    private var generation = 0L
    private var currentPart = 0
    private var prepared = false
    private var seeking = false
    private var submittedOffset = 0L
    private var requestedPosition = 0L
    private var wantsPlayback = false
    private var periodMs = VisualRefreshRate.intervalMillis(VisualRefreshRate.DEFAULT)
    private val audio = app.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var receiverRegistered = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { mutable.value.owner?.let(::pause) }
    }

    fun play(owner: String, id: String, rate: Int = VisualRefreshRate.DEFAULT) {
        if (mutable.value.owner == owner && mutable.value.audioId == id && mutable.value.active) {
            wantsPlayback = true
            if (prepared && !seeking) startReady()
            return
        }
        stopCurrent()
        val token = generation
        wantsPlayback = true
        periodMs = VisualRefreshRate.intervalMillis(rate)
        mutable.value = AudioPlaybackState(owner, id, active = true, preparing = true)
        preparation = app.applicationScope.launch {
            var acquired: Closeable? = null
            try {
                val source = withContext(Dispatchers.IO) {
                    acquired = app.recordingHistory.acquire(id)
                    app.recordingHistory.get(id)
                }
                if (token != generation) return@launch
                lease = acquired; acquired = null
                entry = source
                timeline = AudioTimeline(source.durationMs.coerceAtLeast(1), if (source.sourceName == null) 3600000L else source.durationMs.coerceAtLeast(1))
                mutable.value = mutable.value.copy(durationMs = source.durationMs)
                ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
                openPart(0)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (token == generation) failed(e)
            } finally { withContext(NonCancellable + Dispatchers.IO) { acquired?.close() } }
        }
    }

    fun setRate(owner: String, rate: Int) { if (mutable.value.owner == owner) periodMs = VisualRefreshRate.intervalMillis(rate) }

    fun pause(owner: String) {
        if (mutable.value.owner != owner || !mutable.value.active) return
        wantsPlayback = false
        if (prepared) {
            runCatching { if (player?.isPlaying == true) player?.pause() }
            updatePosition()
        }
        ticker?.cancel(); ticker = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        mutable.value = mutable.value.copy(playing = false)
    }

    fun seek(owner: String, position: Long) {
        if (mutable.value.owner != owner || !mutable.value.active || mutable.value.durationMs <= 0) return
        requestedPosition = position.coerceIn(0, timeline?.durationMs ?: Long.MAX_VALUE)
        mutable.value = mutable.value.copy(positionMs = requestedPosition)
        val location = timeline?.locate(requestedPosition) ?: return
        if (location.part != currentPart) openPart(location.part)
        else if (prepared && !seeking) submitSeek(location.offsetMs)
    }

    fun stop(owner: String) { if (mutable.value.owner == owner) stopCurrent() }
    fun stopAudio(id: String) { if (mutable.value.audioId == id) stopCurrent() }

    private fun openPart(part: Int) {
        ticker?.cancel(); ticker = null
        player?.release(); player = null
        prepared = false; seeking = false; currentPart = part
        mutable.value = mutable.value.copy(preparing = true, playing = false)
        val candidate = MediaPlayer()
        player = candidate
        try {
            candidate.setAudioAttributes(attributes)
            candidate.setDataSource(checkNotNull(entry).part(part).absolutePath)
            candidate.setOnPreparedListener {
                if (player !== candidate) return@setOnPreparedListener
                prepared = true
                // Import metadata can be an estimate (for example VBR audio).
                // Once prepared, a positive native duration is authoritative.
                val duration = if (entry?.sourceName != null) candidate.duration.toLong().takeIf { it > 0 } ?: entry!!.durationMs else timeline!!.durationMs
                if (duration > 0 && entry?.sourceName != null) timeline = AudioTimeline(duration)
                mutable.value = mutable.value.copy(durationMs = duration.coerceAtLeast(0), preparing = false)
                val target = timeline!!.locate(requestedPosition)
                if (target.offsetMs > 0) submitSeek(target.offsetMs)
                else if (wantsPlayback) startReady()
            }
            candidate.setOnSeekCompleteListener {
                if (player !== candidate) return@setOnSeekCompleteListener
                seeking = false
                val target = timeline!!.locate(requestedPosition)
                // A newer drag/tap replaces obsolete native seek work. Never
                // start playback at an older target while a newer one is pending.
                if (target.offsetMs != submittedOffset) submitSeek(target.offsetMs)
                else {
                    mutable.value = mutable.value.copy(preparing = false, positionMs = requestedPosition)
                    if (wantsPlayback) startReady()
                }
            }
            candidate.setOnCompletionListener {
                if (player !== candidate || seeking) return@setOnCompletionListener
                val end = timeline!!.start(currentPart) + timeline!!.duration(currentPart)
                if (entry?.sourceName != null || end >= timeline!!.durationMs) stopCurrent()
                else { requestedPosition = end; openPart(currentPart + 1) }
            }
            candidate.setOnErrorListener { _, what, extra ->
                if (player === candidate) failed(IllegalStateException("Audio playback error $what/$extra"))
                true
            }
            candidate.prepareAsync()
        } catch (e: Exception) { failed(e) }
    }

    private fun submitSeek(offset: Long) {
        try {
            if (player?.isPlaying == true) player?.pause()
            ticker?.cancel(); ticker = null
            submittedOffset = offset
            seeking = true
            mutable.value = mutable.value.copy(preparing = true)
            player?.seekTo(offset, MediaPlayer.SEEK_CLOSEST)
        } catch (e: Exception) { failed(e) }
    }

    private fun startReady() {
        val owner = mutable.value.owner ?: return
        if (!prepared || seeking || !wantsPlayback) return
        if (focus == null) {
            val token = generation
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> if (token == generation && change < 0) pause(owner) }.build()
            if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                wantsPlayback = false
                mutable.value = mutable.value.copy(playing = false, error = "Audio playback is unavailable while another app owns audio focus")
                return
            }
            focus = request
        }
        try {
            player?.start()
            mutable.value = mutable.value.copy(playing = true, preparing = false, error = null)
            ticker?.cancel()
            ticker = app.applicationScope.launch {
                while (isActive && wantsPlayback && prepared && !seeking) { updatePosition(); delay(periodMs) }
            }
        } catch (e: Exception) { failed(e) }
    }

    private fun updatePosition() {
        if (!prepared || seeking) return
        runCatching { timeline!!.start(currentPart) + checkNotNull(player).currentPosition }.onSuccess {
            requestedPosition = if (mutable.value.durationMs > 0) it.coerceIn(0, mutable.value.durationMs) else it.coerceAtLeast(0)
            mutable.value = mutable.value.copy(positionMs = requestedPosition)
        }
    }

    private fun failed(error: Exception) {
        android.util.Log.e("AudioPlayback", "Local playback failed", error)
        stopCurrent()
        mutable.value = mutable.value.copy(error = error.message)
    }
    private fun stopCurrent() {
        generation++
        wantsPlayback = false; prepared = false; seeking = false; requestedPosition = 0
        preparation?.cancel(); preparation = null
        ticker?.cancel(); ticker = null
        player?.release(); player = null
        if (receiverRegistered) { app.unregisterReceiver(noisy); receiverRegistered = false }
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        val previous = lease; lease = null
        app.applicationScope.launch(Dispatchers.IO) { previous?.close() }
        entry = null; timeline = null
        mutable.value = AudioPlaybackState(owner = mutable.value.owner, audioId = mutable.value.audioId)
    }
}
