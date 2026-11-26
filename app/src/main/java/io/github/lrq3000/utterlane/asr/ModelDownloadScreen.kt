package io.github.lrq3000.utterlane.asr

import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

internal fun Window.keepModelDownloadScreenOn(speech: ModelManager.DownloadState, speakers: ModelManager.DownloadState) {
    keepSetupWindowOn(speech is ModelManager.DownloadState.Downloading || speakers is ModelManager.DownloadState.Downloading)
}

/** Android applies this only to a visible window; the power button and ordinary background sleep remain available. */
internal fun Window.keepSetupWindowOn(active: Boolean) {
    if (active) addFlags(FLAG_KEEP_SCREEN_ON) else clearFlags(FLAG_KEEP_SCREEN_ON)
}
