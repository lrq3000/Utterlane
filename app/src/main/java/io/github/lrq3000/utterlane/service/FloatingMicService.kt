package io.github.lrq3000.utterlane.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.util.Log
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.ui.theme.NativeBrandStyle
import java.util.concurrent.atomic.AtomicBoolean

class FloatingMicService : Service() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(io.github.lrq3000.utterlane.settings.AppLanguage.wrap(base))

    companion object {
        private const val TAG = "FloatingMicService"
        const val ACTION_STOP = "io.github.lrq3000.utterlane.STOP_SERVICE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var micButton: ImageView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var microphoneSession: MicrophoneSession? = null
    private var recordingOverlay: io.github.lrq3000.utterlane.ui.RecordingOverlay? = null

    private val isRecording = AtomicBoolean(false)
    private var themeMode = SettingsRepository.THEME_SYSTEM
    private var isIntentionalStop = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()

        // Check overlay permission before setting up floating view
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If restarted by system (intent is null), don't restart - user must explicitly enable
        if (intent == null) {
            Log.i(TAG, "Service restarted by system, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                isIntentionalStop = true
                stopSelf()
                return START_NOT_STICKY
            }
        }

        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(UtterlaneApp.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(UtterlaneApp.NOTIFICATION_ID, notification)
            }
            // Dismiss any failure notification from previous boot attempt
            getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(UtterlaneApp.SERVICE_ALERT_NOTIFICATION_ID)
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 14+ blocks microphone FGS from BOOT_COMPLETED or background
            Log.w(TAG, "Cannot start foreground service from boot/background", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            // Missing RECORD_AUDIO permission for microphone FGS type
            Log.w(TAG, "Missing permission for foreground service", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            // Race condition: app went to background before startForeground completed
            Log.w(TAG, "Cannot start foreground service, app in background", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY  // Don't auto-restart
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Only sync preference to false if intentionally stopped (not restarting for size change)
        if (isIntentionalStop) {
            UtterlaneApp.instance.applicationScope.launch {
                UtterlaneApp.instance.settingsRepository.setServiceEnabled(false)
            }
        }
        microphoneSession?.cancel(discard = false)
        microphoneSession = null
        hideCapturePanel()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        serviceScope.cancel()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_mic, null)
        micButton = floatingView.findViewById(R.id.floating_mic_button)
        updateMicButtonState()
        serviceScope.launch {
            UtterlaneApp.instance.settingsRepository.themeMode.collect { mode ->
                themeMode = mode
                updateMicButtonState()
            }
        }

        // Apply button size from settings
        serviceScope.launch {
            val size = UtterlaneApp.instance.settingsRepository.floatingButtonSize.first()
            val sizeDp = when (size) {
                SettingsRepository.BUTTON_SIZE_SMALL -> 44
                SettingsRepository.BUTTON_SIZE_LARGE -> 72
                else -> 56  // MEDIUM (default)
            }
            val sizePx = (sizeDp * resources.displayMetrics.density).toInt()

            withContext(Dispatchers.Main) {
                val params = micButton.layoutParams
                params.width = sizePx
                params.height = sizePx
                micButton.layoutParams = params
            }
        }

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // Default position - will be updated async once loaded from settings
        val defaultX = 100
        val defaultY = 300

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultX
            y = defaultY
        }

        windowManager.addView(floatingView, layoutParams)

        // Load saved position asynchronously and update layout
        serviceScope.launch {
            val (savedX, savedY) = UtterlaneApp.instance.settingsRepository.buttonPosition.first()
            if (savedX >= 0 && savedY >= 0) {
                withContext(Dispatchers.Main) {
                    layoutParams.x = savedX
                    layoutParams.y = savedY
                    windowManager.updateViewLayout(floatingView, layoutParams)
                }
            }
        }

        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (kotlin.math.abs(deltaX) < 10 && kotlin.math.abs(deltaY) < 10) {
                        // This was a tap, not a drag
                        toggleRecording()
                    } else {
                        // Save new position
                        serviceScope.launch {
                            UtterlaneApp.instance.settingsRepository.setButtonPosition(
                                layoutParams.x,
                                layoutParams.y
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun initializeRecognizer() {
        Log.i(TAG, "initializeRecognizer called")
        serviceScope.launch(Dispatchers.IO) {
            val recognizerManager = UtterlaneApp.instance.recognizerManager
            val modelManager = UtterlaneApp.instance.modelManager
            Log.i(TAG, "Model ready: ${modelManager.isModelReady()}, recognizer ready: ${recognizerManager.isInitialized()}")

            if (modelManager.isModelReady() && !recognizerManager.isInitialized()) {
                val success = recognizerManager.initialize()
                Log.i(TAG, "Recognizer initialization: $success")
            }
        }
    }

    private fun toggleRecording() {
        Log.i(TAG, "toggleRecording called, isRecording=${isRecording.get()}")
        if (isRecording.get()) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (microphoneSession != null) { showToast(getString(R.string.state_processing)); return }
        if (MicrophoneSession.isBusy()) { showToast(getString(R.string.stream_busy)); return }
        // Check mic permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Microphone permission not granted")
            android.widget.Toast.makeText(this, getString(R.string.toast_mic_required), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val recognizerManager = UtterlaneApp.instance.recognizerManager
        Log.i(TAG, "startRecording called, recognizer ready=${recognizerManager.isInitialized()}")

        if (!recognizerManager.isInitialized()) {
            Log.i(TAG, "Capture session will initialize the selected model")
        }

        isRecording.set(true)
        updateMicButtonState()

        val target = TextInjectionService.instance?.captureStreamingTarget() ?: StreamingTextTarget(this) { false }
        recordingOverlay = io.github.lrq3000.utterlane.ui.RecordingOverlay(this).apply {
            onDoneClick = { if (isRecording.get()) stopRecording() }
            onCancelClick = {
                target.close()
                microphoneSession?.cancel(); microphoneSession = null; isRecording.set(false)
                updateMicButtonState(); hideCapturePanel()
            }
            show()
        }
        microphoneSession = UtterlaneApp.instance.microphoneSessions.create(this, serviceScope,
            onText = { delta, store -> target.accept(store); recordingOverlay?.setStatus(delta) },
            onComplete = { store, error ->
                isRecording.set(false); microphoneSession = null; updateMicButtonState()
                hideCapturePanel()
                target.finish(store, preserve = error != null)
                error?.let { showToast(it.message); store?.let { result -> TranscriptRecovery.show(this, result) } }
            }, onCaptureEnded = { isRecording.set(false); updateMicButtonState() }, onWarning = { showToast(it) },
            onSessionClosed = { target.sessionClosed() })
        Log.i(TAG, "Starting incremental audio recording")
        microphoneSession?.let { recordingOverlay?.bind(serviceScope, it); it.start() }
    }

    private fun stopRecording() {
        Log.i(TAG, "stopRecording called")
        isRecording.set(false)
        updateMicButtonState()

        microphoneSession?.stop()
    }

    private fun updateMicButtonState() {
        val recording = isRecording.get()
        micButton.setImageResource(
            if (recording) R.drawable.ic_mic_recording else R.drawable.ic_mic
        )
        if (recording) micButton.setBackgroundResource(R.drawable.mic_button_recording_bg)
        else micButton.background = NativeBrandStyle.waveform(
            NativeBrandStyle.palette(this, themeMode), 0f, oval = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::micButton.isInitialized) updateMicButtonState()
    }

    private fun showFailureNotification() {
        UtterlaneApp.instance.serviceAlertNotification.show(R.string.service_start_floating_mic)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, FloatingMicService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, SettingsActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, UtterlaneApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_utterlane_notification)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun hideCapturePanel() { recordingOverlay?.hide(); recordingOverlay = null }
}
