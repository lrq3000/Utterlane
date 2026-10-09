package io.github.lrq3000.utterlane.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.Closeable

internal enum class EffectKind { NS, AEC, AGC }
internal interface MicrophoneEffectHandle : Closeable {
    val enabled: Boolean
    val hasControl: Boolean
    fun setEnabled(value: Boolean): Int
}
internal interface MicrophoneEffectFactory {
    fun available(kind: EffectKind): Boolean
    fun create(kind: EffectKind, sessionId: Int): MicrophoneEffectHandle?
}
internal data class MicrophoneEffectStatus(val kind: EffectKind, val available: Boolean,
    val requested: Boolean?, val actual: Boolean?, val control: Boolean?, val detail: String)

/** Optional per-AudioRecord effects; capture survives unavailable or rejected controls. */
internal class MicrophoneEffects(sessionId: Int, private val policy: InputPreprocessingPolicy,
    factory: MicrophoneEffectFactory = AndroidMicrophoneEffectFactory) : Closeable {
    private val handles = linkedMapOf<EffectKind, MicrophoneEffectHandle>()
    private val initial = EffectKind.entries.associateWith { kind ->
        var available = false
        var detail = "Unavailable"
        try {
            available = factory.available(kind)
            if (available) {
                val handle = factory.create(kind, sessionId)
                if (handle == null) detail = "No effect handle" else { handles[kind] = handle; detail = "Available" }
            }
        } catch (e: Exception) { detail = e.javaClass.simpleName }
        MicrophoneEffectStatus(kind, available, requested(kind), null, null, detail)
    }

    private fun requested(kind: EffectKind) = when (kind) {
        EffectKind.NS -> policy.ns
        EffectKind.AEC -> policy.aec
        EffectKind.AGC -> policy.agc
    }

    fun refresh(): List<MicrophoneEffectStatus> = EffectKind.entries.map { kind ->
        val base = initial.getValue(kind)
        val handle = handles[kind] ?: return@map base
        try {
            val control = handle.hasControl
            val requested = requested(kind)
            val before = handle.enabled
            val result = if (requested != null && control && before != requested) handle.setEnabled(requested) else null
            val actual = handle.enabled
            base.copy(actual = actual, control = control, detail = when {
                requested == null -> "System state retained"
                !control -> "Control unavailable"
                result != null && result != AudioEffect.SUCCESS -> "Request rejected ($result)"
                actual != requested -> "Requested state not observed"
                else -> "Applied"
            })
        } catch (e: Exception) { base.copy(detail = e.javaClass.simpleName) }
    }
    override fun close() {
        handles.values.forEach { handle ->
            try { handle.close() } catch (e: Exception) { Log.w("MicrophoneEffects", "Effect release failed", e) }
        }
        handles.clear()
    }
}

private object AndroidMicrophoneEffectFactory : MicrophoneEffectFactory {
    override fun available(kind: EffectKind): Boolean = when (kind) {
        EffectKind.NS -> NoiseSuppressor.isAvailable()
        EffectKind.AEC -> AcousticEchoCanceler.isAvailable()
        EffectKind.AGC -> AutomaticGainControl.isAvailable()
    }
    override fun create(kind: EffectKind, sessionId: Int): MicrophoneEffectHandle? {
        val effect = when (kind) {
            EffectKind.NS -> NoiseSuppressor.create(sessionId)
            EffectKind.AEC -> AcousticEchoCanceler.create(sessionId)
            EffectKind.AGC -> AutomaticGainControl.create(sessionId)
        } ?: return null
        return Handle(effect)
    }
    private class Handle(private val effect: AudioEffect) : MicrophoneEffectHandle {
        override val enabled get() = effect.enabled
        override val hasControl get() = effect.hasControl()
        override fun setEnabled(value: Boolean): Int = effect.setEnabled(value)
        override fun close() = effect.release()
    }
}
