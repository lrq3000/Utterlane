package io.github.lrq3000.utterlane.ui

import android.content.Context
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.asr.CaptureSnapshot
import io.github.lrq3000.utterlane.audio.AudioInput
import io.github.lrq3000.utterlane.audio.InputFallbackReason

/** Shared, localized device names and truthful current/past capture messages. */
object AudioInputText {
    fun name(context: Context, input: AudioInput): String = when {
        input.isPhone -> context.getString(R.string.audio_input_phone)
        input.name.isNotBlank() -> input.name
        input.bluetooth -> context.getString(R.string.audio_input_bluetooth)
        else -> context.getString(R.string.audio_input_external)
    }

    fun caption(context: Context, snapshot: CaptureSnapshot): String {
        val input = snapshot.input.actual ?: return context.getString(
            if (snapshot.phase == CapturePhase.CAPTURING || snapshot.phase == CapturePhase.LOADING)
                R.string.audio_input_confirming else R.string.audio_input_unconfirmed)
        val active = snapshot.phase == CapturePhase.CAPTURING
        val message = when {
            active && snapshot.input.connecting -> R.string.audio_input_connecting
            active -> R.string.audio_input_caption
            else -> R.string.audio_input_caption_previous
        }
        return context.getString(message, name(context, input))
    }

    fun warning(context: Context, snapshot: CaptureSnapshot): String? {
        val state = snapshot.input
        val source = state.fallbackFrom ?: return null
        val reason = context.getString(when {
            state.fallbackReason == InputFallbackReason.UNSUPPORTED_ROUTE -> R.string.audio_input_route_unsupported
            state.fallbackReason == InputFallbackReason.MODE_NOT_APPLIED -> R.string.audio_input_mode_not_applied
            state.fallbackReason == InputFallbackReason.ROUTE_CHANGED -> R.string.audio_input_route_changed
            source.bluetooth && state.fallbackReason == InputFallbackReason.DISCONNECTED -> R.string.audio_input_bt_disconnected
            source.bluetooth -> R.string.audio_input_bt_unavailable
            else -> R.string.audio_input_external_unavailable
        })
        val active = snapshot.phase == CapturePhase.CAPTURING
        val message = when {
            active && state.receivingFallback -> R.string.audio_input_continuing
            active -> R.string.audio_input_switching
            state.receivingFallback -> R.string.audio_input_fallback_previous
            else -> R.string.audio_input_fallback_unconfirmed
        }
        return context.getString(message, reason)
    }
}
