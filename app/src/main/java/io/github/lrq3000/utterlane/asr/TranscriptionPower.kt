package io.github.lrq3000.utterlane.asr

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable

/** System broadcasts are hints: always re-read power/keyguard state before acting. */
class DeviceWakeObserver(context: Context, private val changed: () -> Unit) : Closeable {
    private val context = context.applicationContext
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed()
    }
    init {
        ContextCompat.registerReceiver(this.context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun close() = context.unregisterReceiver(receiver)

    companion object {
        fun isInteractive(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isInteractive
        fun canDeliver(context: Context): Boolean = isInteractive(context) &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }
}

/**
 * A lease covers loading, capture and draining, not the lifetime of a UI component.
 * Partial and display locks are deliberately separate: pressing Power still turns
 * the screen off, but should not suspend accepted audio waiting for inference.
 */
class TranscriptionPower(context: Context, private val onAwake: () -> Unit = {}) : Closeable {
    private val context = context.applicationContext
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Utterlane:transcription-cpu").apply { setReferenceCounted(false) }
    // IME/overlay views also use keepScreenOn. The screen lock covers headless
    // RecognitionService clients and gaps while Android replaces those windows.
    // Unlike ACQUIRE_CAUSES_WAKEUP this never turns a deliberately locked phone on.
    @Suppress("DEPRECATION")
    private val screen = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Utterlane:transcription-screen").apply { setReferenceCounted(false) }
    private var closed = false
    private var observer: DeviceWakeObserver? = null
    private val renew = object : Runnable {
        override fun run() {
            synchronized(this@TranscriptionPower) {
                if (closed) return
                refresh()
                handler.postDelayed(this, 60_000)
            }
        }
    }

    init {
        try {
            observer = DeviceWakeObserver(this.context) { stateChanged() }
            synchronized(this) { refresh(); handler.postDelayed(renew, 60_000) }
        } catch (error: Exception) { close(); throw error }
    }

    @Synchronized private fun refresh() {
        // Renewable finite leases protect long dictations without a permanent
        // wake lock if the process can no longer renew it while suspended.
        cpu.acquire(10 * 60_000L)
        if (DeviceWakeObserver.canDeliver(context)) screen.acquire(10 * 60_000L)
        else if (screen.isHeld) screen.release()
    }

    @Synchronized private fun stateChanged() {
        if (closed) return
        refresh()
        // A suspended process may receive SCREEN_OFF only after SCREEN_ON. Do
        // not require observing both edges; recovery is idempotent and owner-run.
        if (power.isInteractive && !power.isDeviceIdleMode) {
            Log.i("TranscriptionPower", "Device awake; retaining session and resuming capture/processing")
            onAwake()
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(renew)
        observer?.close()
        observer = null
        if (screen.isHeld) screen.release()
        if (cpu.isHeld) cpu.release()
    }
}
