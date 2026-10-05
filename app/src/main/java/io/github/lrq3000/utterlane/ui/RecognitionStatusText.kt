package io.github.lrq3000.utterlane.ui

import android.content.Context
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.CaptureSnapshot
import io.github.lrq3000.utterlane.asr.RecognitionStage
import io.github.lrq3000.utterlane.asr.RecognitionStatus

/** Shared wording for Compose file transcription and the native IME/overlay panel. */
object RecognitionStatusText {
    fun activity(context: Context, status: RecognitionStatus): String {
        if (status.stage == RecognitionStage.IDLE) return ""
        val stage = context.getString(when (status.stage) {
            RecognitionStage.IDLE -> R.string.recognition_idle
            RecognitionStage.QUEUED -> R.string.recognition_queued
            RecognitionStage.CONNECTING -> R.string.recognition_connecting
            RecognitionStage.LOADING -> R.string.recognition_loading
            RecognitionStage.WARMUP -> R.string.recognition_warmup
            RecognitionStage.ASR -> R.string.recognition_asr
            RecognitionStage.SPEAKER_LOAD -> R.string.recognition_speaker_load
            RecognitionStage.SPEAKERS -> R.string.recognition_speakers
            RecognitionStage.SPEAKER_FEATURES -> R.string.recognition_features
            RecognitionStage.SPEAKER_TRANSFORMER -> R.string.recognition_transformer
            RecognitionStage.SPEAKER_CACHE -> R.string.recognition_cache
            RecognitionStage.FINISHED -> R.string.recognition_finished
            RecognitionStage.ERROR -> R.string.recognition_error
            RecognitionStage.WORKING -> R.string.recognition_working
        })
        val times = context.getString(R.string.recognition_times, status.elapsedMillis / 1000.0, status.sinceProgressMillis / 1000.0)
        return "$stage · $times" + if (status.opaque) "\n" + context.getString(R.string.recognition_opaque) else ""
    }

    fun backlog(context: Context, snapshot: CaptureSnapshot): String = context.getString(
        R.string.recognition_backlog, snapshot.capturedSeconds, snapshot.processedSeconds, snapshot.backlogSeconds)
}
